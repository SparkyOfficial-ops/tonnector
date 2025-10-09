package dev.tonnector.ui

import dev.tonnector.app.AppController
import dev.tonnector.app.Decision
import dev.tonnector.bridge.links
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun showDesktop(controller: AppController) {
    SwingUtilities.invokeLater {
        DesktopWindow(controller).isVisible = true
    }
}

private class DesktopWindow(private val controller: AppController) : JFrame("TONNECTOR") {
    private val status = JLabel("Disconnected")
    private val identity = JTextArea()
    private val list = JTextArea()
    private val detail = JTextArea()
    private val log = JTextArea()
    private val link = JTextField()
    private var selected: String? = null

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        contentPane.background = Color(11, 18, 32)
        layout = BorderLayout(8, 8)
        identity.isEditable = false
        list.isEditable = false
        detail.isEditable = false
        log.isEditable = false
        list.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        detail.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        identity.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        log.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        for (area in listOf(identity, list, detail, log)) {
            area.background = Color(18, 26, 43)
            area.foreground = Color(232, 238, 248)
            area.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            area.lineWrap = true
            area.wrapStyleWord = true
        }

        val header = JPanel(BorderLayout())
        header.background = Color(18, 26, 43)
        header.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        val title = JLabel("TONNECTOR")
        title.foreground = Color.WHITE
        title.font = title.font.deriveFont(Font.BOLD, 18f)
        status.foreground = Color(255, 180, 180)
        header.add(title, BorderLayout.WEST)
        header.add(status, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)

        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.background = Color(11, 18, 32)
        left.add(scroll(identity, 160))
        link.maximumSize = Dimension(Int.MAX_VALUE, 28)
        left.add(link)
        left.add(buttons(JButton("Open link").apply { addActionListener { openLink() } }))
        left.add(scroll(list, 220))

        val right = JPanel(BorderLayout(0, 8))
        right.background = Color(11, 18, 32)
        right.add(scroll(detail, 280), BorderLayout.CENTER)
        right.add(
            buttons(
                JButton("Approve (Sign)").apply { addActionListener { act(Decision.APPROVE) } },
                JButton("Reject").apply { addActionListener { act(Decision.REJECT) } },
                JButton("Bad request").apply { addActionListener { act(Decision.BAD_REQUEST) } },
                JButton("Not supported").apply { addActionListener { act(Decision.NOT_SUPPORTED) } },
                JButton("Disconnect").apply { addActionListener { controller.disconnectAll() } },
            ),
            BorderLayout.SOUTH,
        )

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.resizeWeight = 0.42
        add(split, BorderLayout.CENTER)
        add(scroll(log, 120), BorderLayout.SOUTH)

        controller.listen {
            SwingUtilities.invokeLater {
                refresh()
                if (it == "request") toFront()
            }
        }
        refresh()
        setSize(1100, 760)
        setLocationRelativeTo(null)
    }

    private fun refresh() {
        val id = controller.wallet.identity
        val links = controller.links()
        val connected = controller.isConnected()
        status.text = if (connected) "Connected" else "Disconnected"
        status.foreground = if (connected) Color(141, 222, 180) else Color(255, 180, 180)
        identity.text = """
            ${id.version}  ${id.network} (${id.globalId})
            ${id.addressRaw}
            ${id.addressFriendly}
            pk ${id.publicKeyHex}
            seqno ${controller.seqno}
            bridge ${links.bridge}
            console ${links.home}
        """.trimIndent()
        val requests = controller.requests().asReversed()
        if (selected == null) {
            selected = requests.firstOrNull { it.second == "pending" }?.first?.id
        }
        list.text = requests.joinToString("\n\n") { (request, state) ->
            val mark = if (request.id == selected) ">" else " "
            "$mark ${request.title} [$state]\n  ${request.summary}"
        }.ifBlank { "No requests yet." }
        detail.text = requests.firstOrNull { it.first.id == selected }?.first?.detail ?: "Select a request in the web console, or the newest one is shown after it arrives.\n\n$id"
        log.text = controller.logLines().takeLast(12).joinToString("\n")
    }

    private fun act(decision: Decision) {
        val id = selected ?: controller.requests().lastOrNull { it.second == "pending" }?.first?.id ?: return
        selected = id
        try {
            if (decision == Decision.APPROVE) controller.approve(id) else controller.reject(id, decision)
        } catch (e: Exception) {
            detail.text = e.message ?: e.toString()
        }
    }

    private fun openLink() {
        val value = link.text.trim()
        if (value.isEmpty()) return
        try {
            val request = controller.openConnectLink(value)
            selected = request.id
        } catch (e: Exception) {
            detail.text = e.message ?: e.toString()
        }
    }

    private fun scroll(area: JTextArea, height: Int): JScrollPane {
        val pane = JScrollPane(area)
        pane.preferredSize = Dimension(400, height)
        pane.border = BorderFactory.createEmptyBorder()
        return pane
    }

    private fun buttons(vararg buttons: JButton): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.background = Color(11, 18, 32)
        buttons.forEach { panel.add(it) }
        return panel
    }
}
