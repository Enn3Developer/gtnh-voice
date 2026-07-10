// Standalone test harness: a hand-rolled Minecraft 1.7.10 + FML client that logs into the dev server
// offline, completes the FML|HS mod-list handshake and reaches PLAY. Deliberately self-contained
// (own repositories/toolchain) so it does not drag in the GTNHGradle mod build.
plugins {
    java
    application
}

repositories {
    mavenCentral()
    // JitPack: source of the pure-Java Concentus Opus codec the mod's JavaOpusDecoder wraps.
    maven { url = uri("https://jitpack.io") }
}

// Security review (round 3): the actual receiver-side decode classes (JavaOpusDecoder, BaseOpusDecoder,
// AdaptiveJitterBuffer + the codec api interfaces) live in the mod's src/main. The mod build compiles them at
// Java 25 (class v69), unreadable by this Java-17 harness toolchain, so instead of linking the mod's binaries
// we pull the exact same source files into this module's compilation (Concentus is the only extra dep they
// need). Same source -> same class the real receiver runs.
sourceSets["main"].java {
    srcDir("${rootProject.projectDir}/src/main/java")
    include("com/enn3developer/gtnhvoice/robustness/**")
    include("com/enn3developer/gtnhvoice/core/audio/codec/opus/JavaOpusDecoder.java")
    include("com/enn3developer/gtnhvoice/core/audio/codec/opus/BaseOpusDecoder.java")
    // Finding #5 fix verification (OpusPoisonFixVerify): encoder side, only to synthesize a genuinely valid
    // frame for the no-regression check. OpusMode comes from the :protocol dependency.
    include("com/enn3developer/gtnhvoice/core/audio/codec/opus/JavaOpusEncoder.java")
    include("com/enn3developer/gtnhvoice/core/audio/codec/opus/BaseOpusEncoder.java")
    include("com/enn3developer/gtnhvoice/core/api/audio/codec/AudioEncoder.java")
    include("com/enn3developer/gtnhvoice/core/audio/jitter/AdaptiveJitterBuffer.java")
    include("com/enn3developer/gtnhvoice/core/api/audio/codec/AudioDecoder.java")
    include("com/enn3developer/gtnhvoice/core/api/audio/codec/AudioDecoderPlc.java")
    include("com/enn3developer/gtnhvoice/core/api/audio/codec/CodecException.java")
}

dependencies {
    // The shared MC-free protocol module: the single source of truth for the voice crypto (VoiceProtocol,
    // AesEncryption) and wire format (PacketUdpCodec + packets, HelloCodec hello framing). Replaces the
    // former hand-copied VoiceCrypto + hand-rolled hello/UDP framing, so a protocol change now breaks this
    // harness at compile time instead of silently desyncing it.
    implementation(project(":protocol"))
    // The protocol module's runtime libraries are provided by Minecraft/Forge inside the mod, but this
    // standalone harness has no game classpath, so they must be declared explicitly here.
    implementation("com.google.guava:guava:17.0")
    implementation("it.unimi.dsi:fastutil-core:8.5.16")
    implementation("org.apache.logging.log4j:log4j-api:2.0-beta9")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.0-beta9")

    // The exact Netty the 1.7.10 client/server use, so wire semantics match.
    implementation("io.netty:netty-all:4.0.10.Final")
    // Only used to parse the FML-augmented Server List Ping (modinfo.modList) response.
    implementation("com.google.code.gson:gson:2.10.1")

    // Round-3 decode-path harness: the Concentus codec the receiver-side JavaOpusDecoder wraps (sources pulled in
    // via the sourceSets include above).
    implementation("com.github.lostromb.concentus:Concentus:3885c4e")
}

java {
    toolchain {
        // Java 17 (mirrors the mod runtime): JDK-native X25519 (XDH) needs Java 11+, so the voice
        // handshake's ECDH can reuse the JDK provider rather than pulling in BouncyCastle.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    // Voice session negotiation (login + FML + ClientHello/ServerHello + a server-accepted UDP
    // packet). The earlier login-only harness is still runnable via -PspikeMain or its class directly.
    mainClass.set("com.enn3developer.gtnhvoice.robustness.Client")
}

// Finding #9: ClientHello burst -> unbounded pendingSends growth. Temporary, do-not-commit harness.
tasks.register<JavaExec>("flood9") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.PendingSendsGrowth")
    args = (project.findProperty("floodArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: unbounded pre-rate-limit allocation in the gtnhvoice control-channel decode.
tasks.register<JavaExec>("allocDos") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.HelloAllocDos")
    args = (project.findProperty("allocArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: serverbound UDP audio path has no rate limit -> server relays one client's audio
// burst to every nearby in-range player at an arbitrary rate (receiver-side resource exhaustion + server egress amplification).
tasks.register<JavaExec>("relayFlood") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.AudioRelayFlood")
    args = (project.findProperty("relayArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: serverbound UDP audio has no replay/dedup - the anti-replay watermark gates only
// address relearning, so a keyless on-path client can resend one captured PlayerAudioPacket datagram and
// the server re-routes it to every in-range player each time (CWE-294 capture-replay).
tasks.register<JavaExec>("audioReplay") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.AudioReplayInjection")
    args = (project.findProperty("replayArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: per-hello entry log fires before the HelloRateLimiter, logging the client's 8KB
// modVersion per hello -> log/disk I/O amplification the finding-#9 gate was meant to prevent.
tasks.register<JavaExec>("helloLogFlood") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.HelloLogFlood")
    args = (project.findProperty("helloArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: the UDP source-address relearn log (VoiceServerSession.relearnAddress) is unthrottled
// and runs on the event-loop thread before the audio rate limiter -> one authenticated client rotating
// its UDP source port with monotonic timestamps fills the server log 1 line/packet (disk + event-loop resource exhaustion).
tasks.register<JavaExec>("relearnLogFlood") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.AddressRelearnLogFlood")
    args = (project.findProperty("relearnArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review (round 3): drive the real receiver-side JavaOpusDecoder with arbitrary relayed bytes
// (a relayed SourceAudioPacket.data) and detect any throwable that escapes the caller's
// CodecException-only guard -> kills the receiver's jitterbuffer poller thread (permanent per-speaker deafness).
tasks.register<JavaExec>("opusFuzz") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.OpusDecodeFuzz")
    args = (project.findProperty("fuzzArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review (round 3): prove a single crafted frame corrupts a fresh receiver decoder (segment-start
// state) 100% deterministically -> reproducible single-shot poller-thread kill.
tasks.register<JavaExec>("opusPoison") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.OpusPoisonFrame")
}

// Security review (round 3): fix verification for finding #5 - asserts the crafted frame now throws a catchable
// CodecException (not AssertionError), nothing escapes across many fresh decoders, and a valid frame still decodes.
tasks.register<JavaExec>("opusPoisonVerify") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.OpusPoisonFixVerify")
}

// Security review (round 3): drive the real AdaptiveJitterBuffer with arbitrary sequence numbers
// to probe integer-overflow / scheduling corruption in the receiver's jitter buffer.
tasks.register<JavaExec>("jitterFuzz") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.JitterBufferProbe")
    args = (project.findProperty("jitterArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review (round 3): a chosen audio sequenceNumber = Long.MAX_VALUE overflows the receiver's
// AdaptiveJitterBuffer schedule so the frame plays immediately and pins VoiceSource.lastEmittedSequence to
// Long.MAX; discardThrough() then silently drops every later frame -> permanent per-speaker deafness that
// survives inactivity resets (no exception, no dead thread - distinct from finding #5).
tasks.register<JavaExec>("jitterSeqPoison") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.JitterSeqPoison")
}

// Security review: does Ping bypassing the per-session audio rate limit let a Ping burst drive enough
// AES-GCM decrypt+touch on the single UDP event-loop thread to degrade legitimate voice? Built-in control:
// same-rate UNKNOWN-session burst (dropped before decrypt) vs VALID ping burst (full decrypt).
tasks.register<JavaExec>("pingFlood") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.PingFloodProbe")
    args = (project.findProperty("pingArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: control-channel decode-exception probe (send clientbound discriminators serverbound).
tasks.register<JavaExec>("decodeProbe") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.ControlDecodeProbe")
    args = (project.findProperty("probeArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

// Security review: malformed wrong-side (clientbound) control discriminators fail in FML's shared codec
// upstream of every gtnhvoice rate limiter, logging 3 full stack traces per packet on the server thread
// -> unthrottled disk-exhaustion + tick-starvation from a single authenticated client.
tasks.register<JavaExec>("decodeFlood") {
    group = "robustness"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.enn3developer.gtnhvoice.robustness.ControlDecodeFlood")
    args = (project.findProperty("decodeArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}
