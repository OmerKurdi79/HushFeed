/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Suppresses TikTok's native hold banners (the plain "Speed: 2x" and the pull-down lock banner)
 * while the interactive speed slider is enabled, so they don't overlap the slider HUD.
 */
internal fun BytecodePatchContext.suppressHoldBanners(receiver: MutableMethod) {
    receiver.requireLocals("Playback speed", 1)
    receiver.addInstructionsWithLabels(
        0,
        """
            invoke-static {}, Lapp/morphe/extension/tiktok/speed/SpeedSliderOverlay;->suppressNativeBanner()Z
            move-result v0
            if-eqz v0, :keep_receiver
            return-void
        """,
        ExternalLabel("keep_receiver", receiver.getInstruction(0)),
    )

    val calls = receiver.implementation!!.instructions.mapNotNull { instruction ->
        instruction.getReference<MethodReference>()?.let { instruction.opcode to it }
    }

    val lockCall = calls.filter { (opcode, reference) ->
        opcode == Opcode.INVOKE_INTERFACE && reference.parameterTypes == listOf("F", "Z") && reference.returnType == "V"
    }.map { it.second }.distinctBy { it.toString() }.singleOrPatchException("Playback speed: hold lock banner call")
    val implementors = mutableListOf<String>()
    classDefForEach { if (lockCall.definingClass in it.interfaces) implementors += it.type }
    val lockBanner = mutableClassDefBy(implementors.singleOrPatchException("Playback speed: hold lock banner"))
    val lockShow = lockBanner.methods.filter {
        it.name == lockCall.name && it.parameterTypes == listOf("F", "Z") && it.returnType == "V"
    }.singleOrPatchException("Playback speed: hold lock banner show")

    lockShow.requireLocals("Playback speed", 1)
    lockShow.addInstructionsWithLabels(
        0,
        """
            invoke-static {}, Lapp/morphe/extension/tiktok/speed/SpeedSliderOverlay;->suppressNativeBanner()Z
            move-result v0
            if-eqz v0, :keep_lock_show
            return-void
        """,
        ExternalLabel("keep_lock_show", lockShow.getInstruction(0)),
    )

    val plain = calls.filter { (opcode, reference) ->
        opcode == Opcode.INVOKE_VIRTUAL && reference.parameterTypes.isEmpty() && reference.returnType == "V" &&
            reference.definingClass != receiver.definingClass &&
            classDefByOrNull(reference.definingClass)?.superclass == "Landroid/widget/LinearLayout;"
    }.map { it.second }.distinctBy { it.toString() }
    val plainBanner = mutableClassDefBy(
        plain.map { it.definingClass }.distinct().singleOrPatchException("Playback speed: plain hold banner"),
    )
    if (plain.size != 2) {
        throw PatchException("Playback speed: the plain hold banner is shown and hidden by ${plain.size} calls, not two.")
    }
    plain.forEach { reference ->
        val bannerMethod = plainBanner.methods.filter {
            it.name == reference.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }.singleOrPatchException("Playback speed: plain hold banner ${reference.name}")
        bannerMethod.requireLocals("Playback speed", 1)
        bannerMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/tiktok/speed/SpeedSliderOverlay;->suppressNativeBanner()Z
                move-result v0
                if-eqz v0, :keep_plain_banner_${reference.name}
                return-void
            """,
            ExternalLabel("keep_plain_banner_${reference.name}", bannerMethod.getInstruction(0)),
        )
    }
}
