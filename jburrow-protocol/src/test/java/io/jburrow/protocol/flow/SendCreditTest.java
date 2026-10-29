package io.jburrow.protocol.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SendCreditTest {

    private static final int MAX = FlowLimits.MAX_WINDOW;

    @Test
    void startsAtThePeersWindow() {
        assertThat(new SendCredit("stream", 65_536).available()).isEqualTo(65_536);
        assertThat(new SendCredit("stream", MAX).available()).isEqualTo(MAX);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void aWindowIsAtLeastOneByte(int initial) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SendCredit("stream", initial))
                .withMessageContaining(String.valueOf(initial));
    }

    @Test
    void spendsToZero() {
        SendCredit credit = new SendCredit("stream", 10);

        credit.spend(4);
        credit.spend(6);

        assertThat(credit.available()).isZero();
    }

    @ParameterizedTest(name = "{1} from {0}")
    @CsvSource({"10, 11", "0, 1", "1, 2", "65536, 65537"})
    void overspendingIsABugAndChangesNothing(int available, int bytes) {
        SendCredit credit = creditOf(available);

        assertThatIllegalStateException()
                .isThrownBy(() -> credit.spend(bytes))
                .withMessage("Sending " + bytes + " bytes exceeds the stream credit of " + available);
        assertThat(credit.available()).isEqualTo(available);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aSendIsAtLeastOneByte(int bytes) {
        SendCredit credit = new SendCredit("stream", 10);

        assertThatIllegalArgumentException().isThrownBy(() -> credit.spend(bytes));
        assertThat(credit.available()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
        "0, 1, 1",
        "10, 5, 15",
        "0, 2147483647, 2147483647",
        "2147483646, 1, 2147483647",
        "1073741824, 1073741823, 2147483647"
    })
    void grantsUpToTheMaximum(int available, int increment, long expected) {
        SendCredit credit = creditOf(available);

        credit.grant(increment);

        assertThat(credit.available()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource({"2147483647, 1", "1, 2147483647", "2147483647, 2147483647", "1073741824, 1073741824"})
    void aGrantPastTheMaximumIsAFlowControlErrorAndChangesNothing(int available, int increment) {
        SendCredit credit = creditOf(available);

        assertThatThrownBy(() -> credit.grant(increment))
                .isInstanceOfSatisfying(ProtocolException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR);
                    assertThat(e.scope()).isEqualTo(ErrorScope.SESSION);
                })
                .hasMessage(
                        "WINDOW_UPDATE of " + increment + " takes the stream credit of " + available + " past " + MAX);
        assertThat(credit.available()).isEqualTo(available);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void anIncrementIsAtLeastOne(int increment) {
        SendCredit credit = new SendCredit("stream", 10);

        assertThatIllegalArgumentException().isThrownBy(() -> credit.grant(increment));
        assertThat(credit.available()).isEqualTo(10);
    }

    @Test
    void aGrantAfterRunningDryResumesSending() {
        SendCredit credit = new SendCredit("session", 10);
        credit.spend(10);

        credit.grant(5);
        credit.spend(5);

        assertThat(credit.available()).isZero();
    }

    @Test
    void aReservationMovesCreditOutOfAvailable() {
        SendCredit credit = new SendCredit("stream", 10);

        credit.reserve(4);

        assertThat(credit.available()).isEqualTo(6);
        assertThat(credit.reserved()).isEqualTo(4);
    }

    @ParameterizedTest(name = "{1} from {0}")
    @CsvSource({"10, 11", "0, 1", "1, 2"})
    void reservingMoreThanIsAvailableIsABugAndChangesNothing(int available, int bytes) {
        SendCredit credit = creditOf(available);

        assertThatIllegalStateException()
                .isThrownBy(() -> credit.reserve(bytes))
                .withMessage("Reserving " + bytes + " bytes exceeds the stream credit of " + available);
        assertThat(credit.available()).isEqualTo(available);
        assertThat(credit.reserved()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aReservationIsAtLeastOneByte(int bytes) {
        SendCredit credit = new SendCredit("stream", 10);

        assertThatIllegalArgumentException().isThrownBy(() -> credit.reserve(bytes));
        assertThat(credit.available()).isEqualTo(10);
    }

    @Test
    void sendingReservedCreditTakesNothingFromAvailable() {
        SendCredit credit = new SendCredit("stream", 10);
        credit.reserve(6);

        credit.spendReserved(4);

        assertThat(credit.available()).isEqualTo(4);
        assertThat(credit.reserved()).isEqualTo(2);
    }

    @Test
    void aRefundReturnsReservedCreditToAvailable() {
        SendCredit credit = new SendCredit("stream", 10);
        credit.reserve(6);

        credit.refund(5);

        assertThat(credit.available()).isEqualTo(9);
        assertThat(credit.reserved()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} of {1} reserved")
    @CsvSource({"Sending, 3", "Refunding, 3", "Sending, 0", "Refunding, 0"})
    void spendingOrRefundingMoreThanIsReservedIsABugAndChangesNothing(String action, int reserved) {
        SendCredit credit = new SendCredit("stream", 10);
        if (reserved > 0) {
            credit.reserve(reserved);
        }
        int bytes = reserved + 1;

        assertThatIllegalStateException()
                .isThrownBy(() -> {
                    if (action.equals("Sending")) {
                        credit.spendReserved(bytes);
                    } else {
                        credit.refund(bytes);
                    }
                })
                .withMessage(action + " " + bytes + " reserved bytes exceeds the stream reservation of " + reserved);
        assertThat(credit.available()).isEqualTo(10 - reserved);
        assertThat(credit.reserved()).isEqualTo(reserved);
    }

    @Test
    void availableReservedAndSentAlwaysAddUpToTheGrant() {
        SendCredit credit = new SendCredit("stream", 100);
        long sent = 0;

        credit.reserve(30);
        credit.spend(20);
        sent += 20;
        credit.spendReserved(10);
        sent += 10;
        credit.grant(50);
        credit.refund(15);
        credit.reserve(40);
        credit.spendReserved(40);
        sent += 40;

        assertThat(credit.available() + credit.reserved() + sent).isEqualTo(150);
        assertThat(credit.reserved()).isEqualTo(5);
    }

    @Test
    void aGrantCountsTheReservationAgainstTheMaximum() {
        SendCredit credit = creditOf(MAX - 10);
        credit.reserve(MAX - 20);

        assertThatThrownBy(() -> credit.grant(11))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("WINDOW_UPDATE of 11 takes the stream credit of " + (MAX - 10) + " past " + MAX);
        credit.grant(10);
        credit.refund(MAX - 20);

        assertThat(credit.available()).isEqualTo(MAX);
        assertThat(credit.reserved()).isZero();
    }

    /** A credit holding exactly {@code available}, reached by spending or granting from a 1-byte window. */
    private static SendCredit creditOf(int available) {
        SendCredit credit = new SendCredit("stream", 1);
        credit.spend(1);
        if (available > 0) {
            credit.grant(available);
        }
        return credit;
    }
}
