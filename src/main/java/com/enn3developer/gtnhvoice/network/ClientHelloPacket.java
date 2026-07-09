package com.enn3developer.gtnhvoice.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * C-&gt;S. Sent once the client player has joined a world and wants to negotiate a voice session.
 * {@code protocolVersion} is read first, before anything else, so a mismatching client is rejected
 * on protocol grounds rather than tripping over a misparsed body.
 * <p>
 * Carries the client's ephemeral 32-byte raw X25519 public key ({@code publicKey}). The client
 * generates one keypair per connection attempt and reuses it across handshake retries, so a retried
 * ClientHello re-offers the same public key and the server's session (keyed by player) stays stable.
 */
public class ClientHelloPacket implements IMessage {

    private byte protocolVersion;
    private String modVersion;
    private byte[] publicKey;

    public ClientHelloPacket() {}

    public ClientHelloPacket(byte protocolVersion, String modVersion, byte[] publicKey) {
        this.protocolVersion = protocolVersion;
        this.modVersion = modVersion;
        this.publicKey = publicKey;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public String getModVersion() {
        return modVersion;
    }

    /** The client's ephemeral raw X25519 public key (32 bytes), or {@code null} if none was sent. */
    public byte[] getPublicKey() {
        return publicKey;
    }

    // The body wire format (incl. the tolerant public-key read) lives in the shared :protocol HelloCodec so
    // the exploit harness and the mod can never drift. SimpleNetworkWrapper hands each IMessage exactly its
    // own payload slice, so the whole remaining buffer is this hello's body.
    @Override
    public void fromBytes(ByteBuf buf) {
        byte[] body = new byte[buf.readableBytes()];
        buf.readBytes(body);
        HelloCodec.ClientHello decoded = HelloCodec.decodeClientHello(body);
        protocolVersion = decoded.protocolVersion;
        modVersion = decoded.modVersion;
        publicKey = decoded.publicKey;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBytes(HelloCodec.encodeClientHello(protocolVersion, modVersion, publicKey));
    }
}
