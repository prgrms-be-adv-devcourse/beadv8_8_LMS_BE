"""kafka-console-consumer 출력(b0.sh dump_topic)을 한 레코드 한 줄로 바꾼다(N13).

입력 구분자는 b0.sh가 고유 토큰으로 준다: 레코드 끝 <<B0EOR>>, 필드 <<B0SEP>>, 헤더 사이 <<B0HDR>>.
DLT 헤더(stacktrace)에는 줄바꿈·탭·쉼표가 들어 있어 일반 구분자로는 나눌 수 없다.
출력: topic \t partition \t offset \t headers \t key \t value
headers는 "키:값"을 쉼표로 이은 것이다. 값은 필요한 헤더(__TypeId__, DLT 예외 클래스)만 남기고 나머지는 키만 남긴다.

사용: python -I normalize_dump.py <토픽> < 콘솔 출력
"""
import sys

KEEP = {"__TypeId__", "kafka_dlt-exception-fqcn", "kafka_dlt-exception-cause-fqcn"}


def main():
    # Windows 콘솔 기본(cp949, CRLF)으로 쓰면 한글 본문이 깨져 verify_b0.py가 읽지 못한다
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    topic = sys.argv[1]
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    for record in raw.split("<<B0EOR>>"):
        record = record.strip("\r\n")
        if not record:
            continue
        fields = record.split("<<B0SEP>>")
        if len(fields) != 5:
            print(f"{topic}\t?\t?\tUNPARSED\t\t{record[:80]!r}")
            continue
        partition, offset, headers, key, value = fields
        entries = []
        if headers != "NO_HEADERS":
            for header in headers.split("<<B0HDR>>"):
                name, _, header_value = header.partition(":")
                entries.append(f"{name}:{header_value}" if name in KEEP else f"{name}:")
        print("\t".join([topic, partition.removeprefix("Partition:"), offset.removeprefix("Offset:"),
                         ",".join(entries), key, value.replace("\t", " ").replace("\n", " ")]))


if __name__ == "__main__":
    main()
