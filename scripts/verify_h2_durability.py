"""Java 17 JAR의 체크인 응답 직후 SIGKILL 및 동일 DB 재시작을 3회 검증한다."""

import csv
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "build/libs/stay-access-recovery-0.0.1-SNAPSHOT.jar"
RESULT = ROOT / "build/h2-durability-result.json"
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
# 사용자 환경의 Spring 설정이 임시 DB 대신 다른 DB를 선택하지 않도록 한다.
APP_ENV = {key: value for key, value in os.environ.items() if not key.startswith("SPRING_")}


def request(port, method, suffix):
    url = f"http://127.0.0.1:{port}/api/reservations/1001{suffix}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=2) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def start(work, phase):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    log_path = work / f"{phase}.log"
    with log_path.open("w") as log:
        process = subprocess.Popen([
            JAVA, "-jar", str(JAR), f"--server.port={port}", "--server.address=127.0.0.1",
            "--spring.config.location=classpath:/application.yml",
            "--app.seed-demo-reservations=true", "--recovery.scheduling.enabled=false",
        ], cwd=work, env=APP_ENV, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("애플리케이션이 시작 중 종료됨")
            try:
                code, _ = request(port, "GET", "/access-grant")
                if code in (200, 409):
                    return process, port
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(0.05)
        raise RuntimeError("애플리케이션 시작 대기 시간 초과")
    except BaseException:
        stop(process)
        print(log_path.read_text(), flush=True)
        raise


def database_snapshot(work, h2):
    snapshot = work / "snapshot.csv"
    sql_path = str(snapshot).replace("'", "''")
    query = ("SELECT R.CHECKED_IN_AT, J.REQUEST_ID, J.STATUS, "
             "(SELECT COUNT(*) FROM ACCESS_GRANT_JOB) AS JOB_COUNT "
             "FROM RESERVATION R JOIN ACCESS_GRANT_JOB J ON J.RESERVATION_ID=R.ID WHERE R.ID=1001")
    result = subprocess.run([
        JAVA, "-cp", str(h2), "org.h2.tools.Shell",
        "-url", f"jdbc:h2:file:{work}/data/access-recovery;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE",
        "-user", "sa", "-password", "", "-sql", f"CALL CSVWRITE('{sql_path}', '{query}')",
    ], check=True, capture_output=True, text=True, timeout=20)
    assert snapshot.exists(), result.stdout + result.stderr
    with snapshot.open(newline="") as file:
        rows = list(csv.DictReader(file))
    assert len(rows) == 1, rows
    return rows[0]


def verify_once(work, h2, run):
    work.mkdir()
    process, port = start(work, "before-kill")
    try:
        # 초기 스키마/예제 예약 저장과 체크인 트랜잭션을 분리한다.
        time.sleep(0.6)
        code, created = request(port, "POST", "/check-in")
        process.kill()
        process.wait(timeout=10)
        assert process.returncode == -signal.SIGKILL, process.returncode
        assert code == 202 and created["status"] == "PENDING" and created["attempts"] == 0, created
    finally:
        stop(process)

    process, port = start(work, "after-restart")
    try:
        code, restored = request(port, "GET", "/access-grant")
        assert code == 200, (code, restored)
        assert restored["requestId"] == created["requestId"], (created, restored)
        assert restored["status"] == "PENDING" and restored["attempts"] == 0, restored
        assert restored["canEnter"] is False, restored
    finally:
        stop(process)

    # 재시작한 앱을 종료한 뒤 DB를 직접 읽어 파일 잠금 충돌을 피한다.
    row = database_snapshot(work, h2)
    assert row["JOB_COUNT"] == "1", row
    assert row["REQUEST_ID"] == created["requestId"] and row["STATUS"] == "PENDING", row
    assert row["CHECKED_IN_AT"], row
    timestamp = row["CHECKED_IN_AT"]
    if timestamp.endswith("+00"):
        timestamp += ":00"
    checked_in_at = datetime.fromisoformat(timestamp)
    # checked_in_at와 최초 nextAttemptAt은 같은 Instant이며 H2는 마이크로초로 저장한다.
    expected = datetime.fromisoformat(created["nextAttemptAt"].replace("Z", "+00:00"))
    assert abs(checked_in_at - expected) <= timedelta(microseconds=1), (row, created)
    result = {"run": run, "checked_in_at": row["CHECKED_IN_AT"], "job_count": 1,
              "requestId": row["REQUEST_ID"], "status": row["STATUS"], "result": "PASS"}
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return result


def main():
    if os.name != "posix":
        raise SystemExit("SIGKILL 검증은 macOS/Linux에서 실행해야 합니다.")
    if not __debug__:
        raise SystemExit("assert 검증을 위해 Python 최적화 옵션(-O)을 사용하지 마세요.")
    if not JAVA or not JAR.is_file():
        raise SystemExit("Java 17을 준비하고 먼저 ./gradlew clean test bootJar를 실행하세요.")
    version = subprocess.run([JAVA, "-version"], check=True, capture_output=True, text=True)
    if 'version "17.' not in version.stdout + version.stderr:
        raise SystemExit("JAVA_HOME 또는 PATH의 java는 Java 17이어야 합니다.")
    RESULT.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="stay-h2-durability-") as directory:
        work = Path(directory)
        h2 = work / "h2.jar"
        with zipfile.ZipFile(JAR) as archive:
            entry = next(name for name in archive.namelist() if name.startswith("BOOT-INF/lib/h2-"))
            h2.write_bytes(archive.read(entry))
        results = [verify_once(work / f"run-{run}", h2, run) for run in range(1, 4)]
    RESULT.write_text(json.dumps({"passed": 3, "runs": results}, ensure_ascii=False, indent=2) + "\n")
    print("PASS: SIGKILL 후 동일 DB 재시작 데이터 보존 3/3", flush=True)


if __name__ == "__main__":
    main()
