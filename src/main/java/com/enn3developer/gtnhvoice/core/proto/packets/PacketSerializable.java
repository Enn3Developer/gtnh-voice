/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets;

import java.io.IOException;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

/**
 * The PacketSerializable interface defines the contract for objects that can be serialized
 * and deserialized to and from byte streams using a {@link ByteArrayDataInput} for deserialization
 * and a {@link ByteArrayDataOutput} for serialization. Implementing classes must provide
 * methods to serialize themselves into a byte stream and deserialize themselves from a byte stream.
 */
public interface PacketSerializable {

    /**
     * Deserialize the object from a byte stream using the provided {@link ByteArrayDataInput}.
     * This method reconstructs the object from its serialized form.
     *
     * @param in The {@link ByteArrayDataInput} containing the serialized data.
     * @throws IOException If an I/O error occurs during deserialization.
     */
    void deserialize(ByteArrayDataInput in) throws IOException;

    /**
     * Serialize the object to a byte stream using the provided {@link ByteArrayDataOutput}.
     * This method converts the object into its serialized form.
     *
     * @param out The {@link ByteArrayDataOutput} to which the serialized data should be written.
     * @throws IOException If an I/O error occurs during serialization.
     */
    void serialize(ByteArrayDataOutput out) throws IOException;
}
