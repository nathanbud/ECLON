package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.deposit.SavingsInterest.Movement;

class SavingsInterestTest {

    static final LocalDate FROM = LocalDate.of(2026, 1, 1), TO = LocalDate.of(2026, 3, 31);

    static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void aQuarterIs90DaysIn2026() {
        assertEquals(90, SavingsInterest.days(FROM, TO));
    }

    @Test
    void aConstantBalanceEarnsRateTimesDaysOver365() {
        BigDecimal sum = SavingsInterest.balanceDays(d("10000.00"), List.of(), FROM, TO);
        assertEquals(0, d("900000.00").compareTo(sum));
        // 10,000 x 1% x 90 / 365 = 24.657 -> 24.66
        assertEquals(d("24.66"), SavingsInterest.interest(sum, 90, d("1.00"), d("500")));
    }

    @Test
    void aDepositCountsFromItsOwnDayAndAWithdrawalAlso() {
        // 1,000 until 15 March, +9,000 on 16 March (16 days of the larger balance: 16..31 March), -4,000 on 31 March
        List<Movement> m = List.of(new Movement(LocalDate.of(2026, 3, 16), d("9000")), new Movement(LocalDate.of(2026, 3, 31), d("-4000")));
        BigDecimal sum = SavingsInterest.balanceDays(d("1000"), m, FROM, TO);
        // days 1 Jan..15 Mar = 74 days at 1,000; 16..30 Mar = 15 days at 10,000; 31 Mar at 6,000
        assertEquals(0, d("74000").add(d("150000")).add(d("6000")).compareTo(sum));
    }

    @Test
    void movementsBeforeThePeriodAreInTheOpeningBalance() {
        List<Movement> m = List.of(new Movement(LocalDate.of(2025, 12, 31), d("500")));
        assertEquals(0, d("135000").compareTo(SavingsInterest.balanceDays(d("1000"), m, FROM, TO)));
    }

    @Test
    void anAverageBalanceUnderTheMinimumEarnsNothing() {
        BigDecimal sum = SavingsInterest.balanceDays(d("499.99"), List.of(), FROM, TO);
        assertEquals(0, BigDecimal.ZERO.compareTo(SavingsInterest.interest(sum, 90, d("1.00"), d("500"))));
        BigDecimal ok = SavingsInterest.balanceDays(d("500.00"), List.of(), FROM, TO);
        assertEquals(d("1.23"), SavingsInterest.interest(ok, 90, d("1.00"), d("500")));
    }

    @Test
    void noRateMeansNoInterest() {
        assertEquals(0, BigDecimal.ZERO.compareTo(SavingsInterest.interest(d("900000"), 90, BigDecimal.ZERO, d("500"))));
    }
}
