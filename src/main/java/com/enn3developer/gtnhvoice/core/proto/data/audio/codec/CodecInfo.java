/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.data.audio.codec;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.Map;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketSerializable;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.google.common.collect.Maps;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public class CodecInfo implements PacketSerializable {

    protected String name;
    protected Map<String, String> params;

    public CodecInfo() {}

    public CodecInfo(String name, Map<String, String> params) {
        this.name = name;
        this.params = params;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getParams() {
        return params;
    }

    @Override
    public void deserialize(ByteArrayDataInput in) throws IOException {
        this.name = in.readUTF();

        this.params = Maps.newHashMap();
        int size = PacketUtil.readSafeInt(in, 0, 128);
        for (int i = 0; i < size; i++) {
            params.put(in.readUTF(), in.readUTF());
        }
    }

    @Override
    public void serialize(ByteArrayDataOutput out) {
        out.writeUTF(checkNotNull(name));
        checkNotNull(params);

        out.writeInt(params.size());
        params.forEach((key, value) -> {
            out.writeUTF(key);
            out.writeUTF(value);
        });
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(name=" + name + ", params=" + params + ")";
    }
}
