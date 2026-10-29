package io.jburrow.protocol.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.ProtocolException;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SessionFlowTest {

    @ParameterizedTest(name = "local {0}/{1}, remote {2}/{3}")
    @CsvSource({"0, 10, 10, 10", "10, 9, 10, 10", "10, 10, 0, 10", "10, 10, 10, 9", "-1, 10, 10, 10", "10, 10, 10, -1"})
    void windowsNeedOneByteAndASessionAtLeastItsStream(
            int localStream, int localSession, int remoteStream, int remoteSession) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessionFlow(localStream, localSession, remoteStream, remoteSession));
    }

    @Test
    void aStreamSendsIntoThePeersWindowAndReceivesIntoThisSides() {
        SessionFlow flow = new SessionFlow(10, 100, 20, 200);

        StreamFlow stream = flow.openStream();

        assertThat(stream.send().available()).isEqualTo(20);
        assertThat(stream.receive().size()).isEqualTo(10);
        assertThat(flow.sessionSend().available()).isEqualTo(200);
        assertThat(flow.sessionReceive().size()).isEqualTo(100);
    }

    @ParameterizedTest(name = "stream {0}, session {1} -> {2}")
    @CsvSource({"20, 200, 20", "20, 20, 20", "200, 200, 200", "20, 30, 20"})
    void sendableIsTheSmallerCredit(int remoteStream, int remoteSession, int expected) {
        SessionFlow flow = new SessionFlow(10, 10, remoteStream, remoteSession);

        assertThat(flow.sendable(flow.openStream())).isEqualTo(expected);
    }

    @Test
    void sendableFollowsTheSessionWhenItIsTheSmaller() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow first = flow.openStream();
        StreamFlow second = flow.openStream();

        flow.onSent(first, 20);

        assertThat(flow.sendable(first)).isZero();
        assertThat(flow.sendable(second)).isEqualTo(10);
    }

    @ParameterizedTest(name = "stream {0}, session {1}")
    @CsvSource({"20, 30", "20, 20", "30, 30"})
    void sendingOneByteMoreThanSendableThrowsAndChangesNeitherCredit(int remoteStream, int remoteSession) {
        SessionFlow flow = new SessionFlow(10, 10, remoteStream, remoteSession);
        StreamFlow stream = flow.openStream();
        int sendable = flow.sendable(stream);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.onSent(stream, sendable + 1))
                .withMessageContaining(String.valueOf(sendable + 1))
                .withMessageContaining("stream credit " + remoteStream)
                .withMessageContaining("session credit " + remoteSession);
        assertThat(stream.send().available()).isEqualTo(remoteStream);
        assertThat(flow.sessionSend().available()).isEqualTo(remoteSession);
    }

    @Test
    void sendingTakesFromBothCredits() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();

        flow.onSent(stream, 7);

        assertThat(stream.send().available()).isEqualTo(13);
        assertThat(flow.sessionSend().available()).isEqualTo(23);
    }

    @Test
    void theSessionWindowCapsSeveralStreamsTogether() {
        SessionFlow flow = new SessionFlow(10, 25, 10, 10);
        StreamFlow first = flow.openStream();
        StreamFlow second = flow.openStream();
        StreamFlow third = flow.openStream();

        flow.onReceived(first, 10);
        flow.onReceived(second, 10);

        assertThatFlowControlError(() -> flow.onReceived(third, 6))
                .hasMessage("DATA of 6 bytes overruns the session window, which has 5 bytes remaining");
        assertThat(third.receive().held()).isZero();
        flow.onReceived(third, 5);
        assertThat(flow.sessionReceive().held()).isEqualTo(25);
    }

    @Test
    void aStreamOverrunLeavesTheSessionWindowUnchanged() {
        SessionFlow flow = new SessionFlow(10, 100, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 4);

        assertThatFlowControlError(() -> flow.onReceived(stream, 7))
                .hasMessage("DATA of 7 bytes overruns the stream window, which has 6 bytes remaining");
        assertThat(flow.sessionReceive().remaining()).isEqualTo(96);
        assertThat(flow.sessionReceive().held()).isEqualTo(4);
        assertThat(stream.receive().held()).isEqualTo(4);
    }

    @Test
    void theSessionWindowIsCheckedBeforeTheStreams() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        StreamFlow first = flow.openStream();
        StreamFlow second = flow.openStream();
        flow.onReceived(first, 8);

        assertThatFlowControlError(() -> flow.onReceived(second, 11)).hasMessageContaining("session window");
    }

    @Test
    void releasingReturnsStreamAndSessionCreditEachAtItsOwnThreshold() {
        SessionFlow flow = new SessionFlow(10, 40, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);

        assertThat(flow.onReleased(stream, 4)).isSameAs(Credit.NONE);
        assertThat(flow.onReleased(stream, 1)).isEqualTo(new Credit(0, 5));
        flow.onReceived(stream, 5);
        StreamFlow other = flow.openStream();
        flow.onReceived(other, 10);

        assertThat(flow.onReleased(other, 10)).isEqualTo(new Credit(0, 10));
        assertThat(flow.onReleased(stream, 10)).isEqualTo(new Credit(25, 10));
        assertThat(flow.sessionReceive().unreturned()).isZero();
    }

    @Test
    void discardedDataCountsAgainstTheSessionAndComesBackAtOnce() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        flow.onReceived(flow.openStream(), 4);

        assertThat(flow.onDiscarded(3)).isSameAs(Credit.NONE);
        assertThat(flow.onDiscarded(2)).isEqualTo(new Credit(5, 0));
        assertThat(flow.sessionReceive().held()).isEqualTo(4);
        assertThat(flow.sessionReceive().remaining()).isEqualTo(6);
    }

    @Test
    void discardedDataPastTheSessionWindowIsAFlowControlError() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        flow.onReceived(flow.openStream(), 8);

        assertThatFlowControlError(() -> flow.onDiscarded(3)).hasMessageContaining("session window");
        assertThat(flow.sessionReceive().held()).isEqualTo(8);
        assertThat(flow.sessionReceive().unreturned()).isZero();
    }

    @Test
    void releasingAfterTheStreamIsGoneReturnsSessionCreditOnly() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        flow.onReceived(flow.openStream(), 10);

        assertThat(flow.onReleased(null, 10)).isEqualTo(new Credit(10, 0));
    }

    @Test
    void creditForAStreamWhosePeerFinishedGoesBackOnTheSessionOnly() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);

        flow.onPeerFinished(stream);

        assertThat(flow.onReleased(stream, 10)).isEqualTo(new Credit(10, 0));
        assertThat(stream.receive().unreturned()).isEqualTo(10);
    }

    @Test
    void releasingMoreThanTheStreamHoldsThrowsAndChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow first = flow.openStream();
        StreamFlow second = flow.openStream();
        flow.onReceived(first, 4);
        flow.onReceived(second, 4);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.onReleased(first, 5))
                .withMessage("Releasing 5 bytes from the stream window, which holds 4");
        assertThat(flow.sessionReceive().held()).isEqualTo(8);
        assertThat(flow.sessionReceive().unreturned()).isZero();
    }

    @Test
    void releasingMoreThanTheSessionHoldsThrowsAndChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        flow.onReceived(flow.openStream(), 4);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.onReleased(null, 5))
                .withMessage("Releasing 5 bytes from the session window, which holds 4");
        assertThat(flow.sessionReceive().held()).isEqualTo(4);
    }

    @Test
    void updatesResumeAStreamAndTheSession() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onSent(stream, 10);
        assertThat(flow.sendable(stream)).isZero();

        flow.onStreamUpdate(stream, 5);
        assertThat(flow.sendable(stream)).isZero();
        flow.onSessionUpdate(5);

        assertThat(flow.sendable(stream)).isEqualTo(5);
    }

    @Test
    void aStreamUpdatePastTheMaximumIsAFlowControlError() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);
        StreamFlow stream = flow.openStream();

        assertThatFlowControlError(() -> flow.onStreamUpdate(stream, FlowLimits.MAX_WINDOW))
                .hasMessageContaining("stream credit of 10");
        assertThat(stream.send().available()).isEqualTo(10);
    }

    @Test
    void aSessionUpdatePastTheMaximumIsAFlowControlError() {
        SessionFlow flow = new SessionFlow(10, 10, 10, 10);

        assertThatFlowControlError(() -> flow.onSessionUpdate(FlowLimits.MAX_WINDOW))
                .hasMessageContaining("session credit of 10");
        assertThat(flow.sessionSend().available()).isEqualTo(10);
    }

    @ParameterizedTest(name = "max {0}, stream {1}, session {2} -> {3}")
    @CsvSource({
        "16, 20, 30, 16",
        "16, 10, 30, 10",
        "16, 20, 12, 12",
        "0, 20, 30, 0",
        "20, 20, 20, 20",
        "2147483647, 20, 30, 20"
    })
    void aReservationTakesTheSmallestOfTheAskTheStreamAndTheSession(
            int max, int streamCredit, int sessionCredit, int expected) {
        SessionFlow flow = new SessionFlow(10, 10, streamCredit, Math.max(streamCredit, sessionCredit));
        StreamFlow stream = flow.openStream();
        if (sessionCredit < streamCredit) {
            flow.onSent(flow.openStream(), streamCredit - sessionCredit);
        }

        assertThat(flow.reserve(stream, max)).isEqualTo(expected);

        assertThat(stream.reserved()).isEqualTo(expected);
        assertThat(flow.reserved()).isEqualTo(expected);
        assertThat(stream.send().available()).isEqualTo(streamCredit - expected);
        assertThat(flow.sessionSend().available()).isEqualTo(sessionCredit - expected);
    }

    @Test
    void reservingWithStreamCreditButNoSessionCreditReservesNothing() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 20);
        StreamFlow stream = flow.openStream();
        flow.onSent(flow.openStream(), 20);

        assertThat(flow.reserve(stream, 16)).isZero();

        assertThat(stream.reserved()).isZero();
        assertThat(stream.send().available()).isEqualTo(20);
        assertThat(flow.reserved()).isZero();
    }

    @Test
    void aReservationAsksForAtLeastZeroBytes() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 20);
        StreamFlow stream = flow.openStream();

        assertThatIllegalArgumentException().isThrownBy(() -> flow.reserve(stream, -1));
        assertThat(flow.sendable(stream)).isEqualTo(20);
    }

    @Test
    void reservedCreditIsSendableByNoStream() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow reserving = flow.openStream();
        StreamFlow other = flow.openStream();

        flow.reserve(reserving, 16);

        assertThat(flow.sendable(reserving)).isEqualTo(4);
        assertThat(flow.sendable(other)).isEqualTo(14);
        assertThatIllegalStateException().isThrownBy(() -> flow.onSent(reserving, 5));
        assertThatIllegalStateException().isThrownBy(() -> flow.onSent(other, 15));
    }

    @Test
    void sendingReservedCreditTakesNoCreditAgain() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 16);

        flow.onSentReserved(stream, 10);
        flow.onSentReserved(stream, 6);

        assertThat(stream.reserved()).isZero();
        assertThat(flow.reserved()).isZero();
        assertThat(stream.send().available()).isEqualTo(4);
        assertThat(flow.sessionSend().available()).isEqualTo(14);
    }

    @Test
    void sendingMoreThanIsReservedThrowsAndChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 8);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.onSentReserved(stream, 9))
                .withMessage("Sending 9 reserved bytes exceeds the stream reservation of 8");
        assertThat(stream.reserved()).isEqualTo(8);
        assertThat(flow.reserved()).isEqualTo(8);
        assertThat(flow.sendable(stream)).isEqualTo(12);
    }

    @Test
    void oneStreamsReservationIsNotAnothers() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow reserving = flow.openStream();
        StreamFlow other = flow.openStream();
        flow.reserve(reserving, 8);

        assertThatIllegalStateException().isThrownBy(() -> flow.onSentReserved(other, 1));
        assertThatIllegalStateException().isThrownBy(() -> flow.refund(other, 1));
        assertThat(flow.reserved()).isEqualTo(8);
    }

    @Test
    void aRefundReturnsWhatTheReadDidNotUse() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 16);
        flow.onSentReserved(stream, 5);

        flow.refund(stream, 11);

        assertThat(stream.reserved()).isZero();
        assertThat(flow.reserved()).isZero();
        assertThat(stream.send().available()).isEqualTo(15);
        assertThat(flow.sessionSend().available()).isEqualTo(25);
    }

    @Test
    void aRefundOfZeroChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 16);
        flow.onSentReserved(stream, 16);

        flow.refund(stream, 0);

        assertThat(flow.sendable(stream)).isEqualTo(4);
    }

    @Test
    void refundingMoreThanIsReservedThrowsAndChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 16);
        flow.onSentReserved(stream, 10);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.refund(stream, 7))
                .withMessage("Refunding 7 reserved bytes exceeds the stream reservation of 6");
        assertThatIllegalArgumentException().isThrownBy(() -> flow.refund(stream, -1));
        assertThat(stream.reserved()).isEqualTo(6);
        assertThat(flow.reserved()).isEqualTo(6);
        assertThat(flow.sessionSend().available()).isEqualTo(14);
    }

    @Test
    void aStreamThatEndsRefundsItsWholeReservationOnce() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow ended = flow.openStream();
        StreamFlow other = flow.openStream();
        flow.reserve(ended, 16);
        flow.onSentReserved(ended, 4);

        flow.refundAll(ended);
        flow.refundAll(ended);

        assertThat(flow.reserved()).isZero();
        assertThat(flow.sessionSend().available()).isEqualTo(26);
        assertThat(flow.sendable(other)).isEqualTo(20);
        assertThatIllegalStateException().isThrownBy(() -> flow.refund(ended, 1));
        assertThatIllegalStateException().isThrownBy(() -> flow.onSentReserved(ended, 1));
    }

    @Test
    void refundingAfterTheStreamLeftItsTableStillReturnsTheSessionsPart() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 16);

        flow.onRemoved(stream);
        flow.refundAll(stream);

        assertThat(flow.reserved()).isZero();
        assertThat(flow.sessionSend().available()).isEqualTo(30);
    }

    @Test
    void aStreamThatLeftItsTableCannotReserve() {
        SessionFlow flow = new SessionFlow(10, 10, 20, 30);
        StreamFlow stream = flow.openStream();
        flow.onRemoved(stream);

        assertThatIllegalStateException().isThrownBy(() -> flow.reserve(stream, 16));
        assertThat(flow.reserved()).isZero();
        assertThat(flow.sessionSend().available()).isEqualTo(30);
    }

    @Test
    void anUpdateCountsTheReservationAgainstTheMaximum() {
        SessionFlow flow = new SessionFlow(10, 10, 10, FlowLimits.MAX_WINDOW);
        StreamFlow stream = flow.openStream();
        flow.reserve(stream, 10);

        assertThatFlowControlError(() -> flow.onSessionUpdate(1))
                .hasMessageContaining("session credit of " + FlowLimits.MAX_WINDOW);
        assertThat(flow.sessionSend().available()).isEqualTo(FlowLimits.MAX_WINDOW - 10);
    }

    @Test
    void parkingReturnsTheHeldBytesSessionCreditButNotTheirStreamCredit() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);

        assertThat(flow.park(stream)).isEqualTo(new Credit(10, 0));

        assertThat(stream.parked()).isEqualTo(10);
        assertThat(stream.receive().held()).isEqualTo(10);
        assertThat(stream.receive().remaining()).isZero();
        assertThat(flow.parked()).isEqualTo(10);
        assertThat(flow.sessionReceive().held()).isZero();
        assertThat(flow.sessionReceive().remaining()).isEqualTo(20);
    }

    @Test
    void parkingBelowHalfTheSessionWindowReturnsNothingYet() {
        SessionFlow flow = new SessionFlow(10, 40, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);

        assertThat(flow.park(stream)).isSameAs(Credit.NONE);

        assertThat(flow.sessionReceive().unreturned()).isEqualTo(10);
        assertThat(flow.parked()).isEqualTo(10);
    }

    @Test
    void parkingWithNothingHeldOrParkingTwiceParksNothing() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();

        assertThat(flow.park(stream)).isSameAs(Credit.NONE);
        flow.onReceived(stream, 10);
        flow.park(stream);
        assertThat(flow.park(stream)).isSameAs(Credit.NONE);

        assertThat(flow.parked()).isEqualTo(10);
        assertThat(flow.sessionReceive().unreturned()).isZero();
    }

    @Test
    void bytesReceivedAfterParkingCountAgainstTheSessionUntilParkedToo() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 4);
        flow.park(stream);

        flow.onReceived(stream, 6);

        assertThat(flow.sessionReceive().held()).isEqualTo(6);
        assertThat(stream.parked()).isEqualTo(4);
        assertThat(flow.park(stream)).isEqualTo(new Credit(10, 0));
        assertThat(flow.parked()).isEqualTo(10);
    }

    @Test
    void releasingParkedBytesReturnsStreamCreditOnly() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);
        flow.park(stream);

        assertThat(flow.onReleased(stream, 4)).isSameAs(Credit.NONE);
        assertThat(flow.onReleased(stream, 6)).isEqualTo(new Credit(0, 10));

        assertThat(stream.parked()).isZero();
        assertThat(flow.parked()).isZero();
        assertThat(flow.sessionReceive().held()).isZero();
        assertThat(flow.sessionReceive().unreturned()).isZero();
    }

    @Test
    void aReleaseTakesParkedBytesFirstAndTheRestFromTheSession() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 4);
        flow.park(stream);
        flow.onReceived(stream, 6);

        assertThat(flow.onReleased(stream, 7)).isEqualTo(new Credit(0, 7));

        assertThat(stream.parked()).isZero();
        assertThat(flow.parked()).isZero();
        assertThat(flow.sessionReceive().held()).isEqualTo(3);
        assertThat(flow.sessionReceive().unreturned()).isEqualTo(7);
        assertThat(flow.onReleased(stream, 3)).isEqualTo(new Credit(10, 0));
    }

    @Test
    void releasingMoreThanAParkedStreamHoldsThrowsAndChangesNothing() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 4);
        flow.park(stream);
        flow.onReceived(flow.openStream(), 8);

        assertThatIllegalStateException()
                .isThrownBy(() -> flow.onReleased(stream, 5))
                .withMessage("Releasing 5 bytes from the stream window, which holds 4");
        assertThat(stream.parked()).isEqualTo(4);
        assertThat(flow.parked()).isEqualTo(4);
        assertThat(flow.sessionReceive().held()).isEqualTo(8);
    }

    @Test
    void aStreamThatLeftItsTableReleasesParkedBytesWithoutCreditAndTheRestForTheSessionOnly() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 6);
        flow.park(stream);
        flow.onReceived(stream, 4);

        flow.onRemoved(stream);

        assertThat(flow.onReleased(stream, 6)).isSameAs(Credit.NONE);
        assertThat(flow.onReleased(stream, 4)).isEqualTo(new Credit(10, 0));
        assertThat(flow.parked()).isZero();
        assertThat(flow.sessionReceive().held()).isZero();
        assertThat(stream.receive().unreturned()).isEqualTo(10);
    }

    @Test
    void aStreamThatLeftItsTableCannotPark() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 10);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 6);
        flow.onRemoved(stream);

        assertThatIllegalStateException().isThrownBy(() -> flow.park(stream));
        assertThat(stream.parked()).isZero();
        assertThat(flow.sessionReceive().held()).isEqualTo(6);
    }

    @Test
    void parkedBytesAddUpAcrossStreams() {
        SessionFlow flow = new SessionFlow(10, 40, 10, 10);
        StreamFlow first = flow.openStream();
        StreamFlow second = flow.openStream();
        flow.onReceived(first, 10);
        flow.onReceived(second, 7);

        flow.park(first);
        flow.park(second);
        flow.onReleased(first, 3);

        assertThat(flow.parked()).isEqualTo(14);
        assertThat(flow.sessionReceive().held()).isZero();
    }

    @Test
    void parkingTouchesNoSendCreditAndReservingNoReceiveWindow() {
        SessionFlow flow = new SessionFlow(10, 20, 10, 30);
        StreamFlow stream = flow.openStream();
        flow.onReceived(stream, 10);

        flow.park(stream);
        flow.reserve(stream, 8);

        assertThat(stream.send().available()).isEqualTo(2);
        assertThat(flow.sessionSend().available()).isEqualTo(22);
        assertThat(stream.receive().held()).isEqualTo(10);
        assertThat(flow.sessionReceive().held()).isZero();
        assertThat(flow.sessionReceive().remaining()).isEqualTo(20);
    }

    @Test
    void creditIsNeverNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Credit(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Credit(0, -1));
        assertThat(Credit.NONE.isEmpty()).isTrue();
        assertThat(new Credit(1, 0).isEmpty()).isFalse();
        assertThat(new Credit(0, 1).isEmpty()).isFalse();
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertThatFlowControlError(Runnable call) {
        return assertThatThrownBy(call::run).isInstanceOfSatisfying(ProtocolException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR);
            assertThat(e.scope()).isEqualTo(ErrorScope.SESSION);
        });
    }
}
