package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NkCreateCommitWireTest {
  @ParameterizedTest(name = "잘못된 backend frame={0}")
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  @DisplayName("backend 응답의 EOF나 읽기 오류는 양방향 소켓을 모두 종료한다")
  void closesBothPeersWhenBackendRepliesEnd(boolean invalidFrame) throws Exception {
    try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        NkCreateCommitWire wire =
            new NkCreateCommitWire(
                "127.0.0.1", backend.getLocalPort(), NkCreateCommitWire.Loss.NONE);
        Socket client = new Socket("127.0.0.1", wire.port())) {
      backend.setSoTimeout(5_000);
      client.setSoTimeout(5_000);
      DataOutputStream startup = new DataOutputStream(client.getOutputStream());
      startup.writeInt(9);
      startup.writeInt(196608);
      startup.writeByte(0);
      startup.flush();

      try (Socket upstream = backend.accept()) {
        upstream.setSoTimeout(5_000);
        DataInputStream forwarded = new DataInputStream(upstream.getInputStream());
        assertThat(forwarded.readInt()).isEqualTo(9);
        assertThat(forwarded.readInt()).isEqualTo(196608);
        assertThat(forwarded.readUnsignedByte()).isZero();

        if (invalidFrame) {
          DataOutputStream reply = new DataOutputStream(upstream.getOutputStream());
          reply.writeByte('Z');
          reply.writeInt(3);
          reply.flush();
        } else {
          upstream.shutdownOutput();
        }

        assertThat(client.getInputStream().read())
            .as("client sees EOF before wire.close()")
            .isEqualTo(-1);
        assertThat(forwarded.read()).as("upstream sees EOF before wire.close()").isEqualTo(-1);
      }
    }
  }
}
