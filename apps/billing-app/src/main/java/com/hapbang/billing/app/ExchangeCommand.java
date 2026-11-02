package com.hapbang.billing.app;

// 환전 신청 내용 (#285). 계좌는 신청마다 입력하는 테스트 계좌이며 실제 존재 여부를 확인하지 않는다.
// 형식(은행 코드 숫자 3자리, 계좌번호 숫자 8~20자리, 예금주 1~50자)은 API 요청 DTO가 먼저 검사한다.
public record ExchangeCommand(long amountHp, String bankCode, String accountNumber, String accountHolderName) {

    // 개인정보가 로그·예외 메시지에 남지 않게 계좌번호·예금주는 출력하지 않는다.
    @Override
    public String toString() {
        return "ExchangeCommand[amountHp=" + amountHp + ", bankCode=" + bankCode + "]";
    }
}
