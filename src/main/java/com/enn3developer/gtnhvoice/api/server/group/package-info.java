/**
 * The addon integration surface for voice group routing: implement
 * {@link com.enn3developer.gtnhvoice.api.server.group.IGroup}
 * (respecting its threading contract) and register it via
 * {@link com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi#groupManager()}.
 */
@API(owner = "gtnhvoice", apiVersion = "1.0", provides = "GtnhVoice|GroupAPI")
package com.enn3developer.gtnhvoice.api.server.group;

import cpw.mods.fml.common.API;
