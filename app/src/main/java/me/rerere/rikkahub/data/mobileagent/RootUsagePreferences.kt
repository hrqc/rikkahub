package me.rerere.rikkahub.data.mobileagent

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** A remembered choice and history, never a persisted Root grant. */
data class RootUsageSettings(
    val enabled: Boolean = true,
    val previouslyVerified: Boolean = false,
    val autoVerificationAllowed: Boolean = false,
)

internal interface RootUsagePreferences {
    fun read(): RootUsageSettings
    fun write(value: RootUsageSettings)
}

internal class MemoryRootUsagePreferences(private var value: RootUsageSettings = RootUsageSettings()) : RootUsagePreferences {
    override fun read() = value
    override fun write(value: RootUsageSettings) { this.value = value }
}

/** The authorization history must not migrate to a different phone through Android backup. */
internal class LocalRootUsagePreferences(context: Context) : RootUsagePreferences {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mobile_agent_root_preferences"))

    override fun read(): RootUsageSettings = try {
        DataInputStream(file.openRead()).use {
            if (it.readInt() != 1) RootUsageSettings()
            else RootUsageSettings(it.readBoolean(), it.readBoolean(), it.readBoolean())
        }
    } catch (_: Exception) { RootUsageSettings() }

    override fun write(value: RootUsageSettings) {
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeBoolean(value.enabled)
            output.writeBoolean(value.previouslyVerified)
            output.writeBoolean(value.autoVerificationAllowed)
            output.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
