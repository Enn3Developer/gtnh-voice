package com.enn3developer.gtnhvoice.network;

import cpw.mods.fml.common.network.ByteBufUtils;
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

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        modVersion = ByteBufUtils.readUTF8String(buf);
        // Read the public key tolerantly: a version-mismatched peer may send a differently-shaped
        // body, and we still want handleClientHello to run and issue a clean version-mismatch reject
        // rather than throwing here. A correct v4 client always includes the full 32 bytes.
        if (buf.readableBytes() >= VoiceProtocol.X25519_PUBLIC_KEY_LENGTH) {
            publicKey = new byte[VoiceProtocol.X25519_PUBLIC_KEY_LENGTH];
            buf.readBytes(publicKey);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        ByteBufUtils.writeUTF8String(buf, modVersion);
        if (publicKey != null) buf.writeBytes(publicKey);
    }
}
