package org.instagene.app.gui.menu

import org.instagene.app.gui.dialog.SettingsDialog
import org.instagene.app.gui.prefs.Prefs
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem

class PreferencesMenu(private val prefs: Prefs) {

    fun create(): JMenu {
        return JMenu("Preferences").apply {
            mnemonic = KeyEvent.VK_R
            add(JMenuItem("Settings...").apply {
                addActionListener { SettingsDialog.show(null, prefs) }
            })
        }
    }
}
