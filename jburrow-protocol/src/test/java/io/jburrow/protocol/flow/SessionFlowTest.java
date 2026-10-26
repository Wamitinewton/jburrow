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
