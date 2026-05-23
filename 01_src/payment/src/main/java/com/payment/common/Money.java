package com.payment.common;

/**
 * 금액 값 객체. 한국 원화는 소수점이 없어 원 단위 정수(long)로 표현한다(설계 7-1).
 * 음수 금액과 음수 결과 차감을 불변식으로 금지하여 잔액 음수 방지(설계 9-5)를 도메인에서 보장한다.
 */
public record Money(long value) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (value < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + value);
        }
    }

    public static Money won(long value) {
        return new Money(value);
    }

    public Money plus(Money other) {
        return new Money(this.value + other.value);
    }

    /** 결과가 음수면 예외. 차감 전 {@link #isGreaterThanOrEqualTo}로 충분성을 먼저 확인할 것. */
    public Money minus(Money other) {
        if (this.value < other.value) {
            throw new IllegalArgumentException(
                    "차감 결과가 음수입니다: " + this.value + " - " + other.value);
        }
        return new Money(this.value - other.value);
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return this.value >= other.value;
    }

    public boolean isLessThan(Money other) {
        return this.value < other.value;
    }

    public boolean isZero() {
        return this.value == 0;
    }

    public boolean isPositive() {
        return this.value > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.value, other.value);
    }
}
