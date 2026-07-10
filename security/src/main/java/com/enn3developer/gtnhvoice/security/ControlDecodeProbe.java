package com.enn3developer.gtnhvoice.security;

/**
 * Secondary probe: send discriminators that are registered on the {@code gtnhvoice} channel but whose
 * handlers are CLIENT-side (ServerHello=1, ServerReject=2, RosterSnapshot=3, RosterUpdate=4,
 * GroupUpdate=5) to the SERVER, with deliberately truncated bodies. FML's shared channel codec decodes
 * every registered discriminator regardless of side, so the server runs the clientbound packet's
 * {@code fromBytes} on its Netty thread; a truncated body throws mid-decode. This probe measures
 * whether that decode exception has any server-side impact (disconnect of others, crash, log spam)
 * beyond dropping the attacker's own packet.
 */
public final class ControlDecodeProbe {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String username = args.length > 2 ? args[2] : "mallory";

        try (VoiceSession s = Client.connect(host, port)
            .username(username)
            .establish()) {

            System.out.println("[probe] session up; sending truncated clientbound discriminators serverbound");

            // disc 1..5 with empty / 1-byte bodies -> readByte / readLong / readFully all underflow.
            for (int disc = 1; disc <= 5; disc++) {
                s.sendControl(disc, new byte[0]);
                s.sendControl(disc, new byte[] { 0x04 });
                Thread.sleep(100);
                System.out.println("[probe] fired disc=" + disc + " truncated");
            }

            System.out.println("[probe] post-probe: is our own connection still alive? sending a ping");
            s.ping();
            Thread.sleep(1500);
        }
    }

    private ControlDecodeProbe() {}
}
