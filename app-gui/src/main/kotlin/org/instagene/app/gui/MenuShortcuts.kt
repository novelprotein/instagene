package org.instagene.app.gui

import java.awt.Toolkit
import java.awt.event.InputEvent
import javax.swing.KeyStroke

/**
 * Menu accelerator for [keyCode] using the platform's modifier key:
 * Command on macOS, Control elsewhere. Falls back to Control in headless
 * environments, where no toolkit is available.
 */
internal fun menuShortcut(keyCode: Int): KeyStroke {
    val mask = runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
        .getOrDefault(InputEvent.CTRL_DOWN_MASK)
    return KeyStroke.getKeyStroke(keyCode, mask)
}