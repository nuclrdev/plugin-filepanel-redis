/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.redis.panel;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.connection.RedisUri;

/** F7 "New connection" / F4 "Edit connection" form. */
public final class ConnectionEditorDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	/**
	 * {@code secret} is what was typed, or {@code null} when the box was left blank (on an
	 * edit: keep the stored one, unless Remember was unticked).
	 */
	public record Result(RedisConnectionProfile profile, String secret, boolean rememberSecret) {
	}

	/** Tries a connection without saving it; returns the text to show, or throws with the failure. */
	@FunctionalInterface
	public interface Tester {
		String test(RedisConnectionProfile profile, String typedSecret) throws Exception;
	}

	private final JTextField nameField = new JTextField(30);
	private final JTextField hostField = new JTextField(30);
	private final JTextField portField = new JTextField(6);
	private final JCheckBox authBox = new JCheckBox(tr("editor.auth"));
	private final JTextField usernameField = new JTextField(20);
	private final JPasswordField secretField = new JPasswordField(20);
	private final JCheckBox rememberBox = new JCheckBox(tr("secret.remember"), true);
	private final JCheckBox tlsBox = new JCheckBox(tr("editor.tls"));
	private final JTextField separatorField = new JTextField(4);
	private final JCheckBox readOnlyBox = new JCheckBox(tr("editor.read.only"));

	private final JTextArea statusArea = new JTextArea(3, 1);
	private final JButton testButton = new JButton(tr("editor.test"));

	private final Tester tester;
	private String idBeingEdited;
	private Result result;

	private ConnectionEditorDialog(Window owner, String title, Tester tester) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		this.tester = tester;
		buildUi();
	}

	public static Result showNew(Window owner, Tester tester) {
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.new"), tester);
		dialog.hostField.setText("localhost");
		dialog.portField.setText(String.valueOf(RedisConnectionProfile.DEFAULT_PORT));
		dialog.separatorField.setText(RedisConnectionProfile.DEFAULT_SEPARATOR);
		dialog.onAuthChanged();
		dialog.setVisible(true);
		return dialog.result;
	}

	/** @param secretStored whether a password is already in the keychain */
	public static Result showEdit(Window owner, RedisConnectionProfile existing, boolean secretStored, Tester tester) {
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.edit"), tester);
		dialog.populateFrom(existing, secretStored);
		dialog.setVisible(true);
		return dialog.result;
	}

	private void populateFrom(RedisConnectionProfile p, boolean secretStored) {
		idBeingEdited = p.getId();
		nameField.setText(p.getName());
		hostField.setText(p.getHost());
		portField.setText(String.valueOf(p.getEffectivePort()));
		authBox.setSelected(p.isAuth());
		usernameField.setText(p.getUsername());
		rememberBox.setSelected(secretStored || !p.isAuth());
		if (secretStored) {
			secretField.setToolTipText(tr("editor.secret.keep.tooltip"));
		}
		tlsBox.setSelected(p.isTls());
		separatorField.setText(p.getEffectiveSeparator());
		readOnlyBox.setSelected(p.isReadOnly());
		onAuthChanged();
	}

	private void buildUi() {
		authBox.addActionListener(e -> onAuthChanged());
		testButton.addActionListener(e -> runTest());
		hostField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				absorbUri();
			}
		});
		hostField.setToolTipText(tr("editor.host.tooltip"));
		usernameField.setToolTipText(tr("editor.username.tooltip"));
		separatorField.setToolTipText(tr("editor.separator.tooltip"));
		readOnlyBox.setToolTipText(tr("editor.read.only.tooltip"));

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
		int row = 0;
		row = addRow(form, row, new JLabel(tr("editor.name")), nameField);
		row = addRow(form, row, new JLabel(tr("editor.host")), hostField);
		row = addRow(form, row, null, hint(tr("editor.host.hint")));
		var portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		portRow.add(portField);
		row = addRow(form, row, new JLabel(tr("editor.port")), portRow);
		row = addRow(form, row, null, authBox);
		row = addRow(form, row, new JLabel(tr("editor.username")), usernameField);
		row = addRow(form, row, new JLabel(tr("editor.password")), secretField);
		row = addRow(form, row, null, rememberBox);
		row = addRow(form, row, null, tlsBox);
		var separatorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		separatorRow.add(separatorField);
		row = addRow(form, row, new JLabel(tr("editor.separator")), separatorRow);
		addRow(form, row, null, readOnlyBox);

		statusArea.setEditable(false);
		statusArea.setLineWrap(true);
		statusArea.setWrapStyleWord(true);
		statusArea.setOpaque(false);
		var statusScroll = new JScrollPane(statusArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		statusScroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

		var ok = new JButton(tr("common.ok"));
		var cancel = new JButton(tr("common.cancel"));
		ok.addActionListener(e -> onOk());
		cancel.addActionListener(e -> dispose());
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(testButton);
		buttons.add(ok);
		buttons.add(cancel);

		var south = new JPanel(new BorderLayout());
		south.add(statusScroll, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.SOUTH);

		var content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
		content.add(form, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		setContentPane(content);
		getRootPane().setDefaultButton(ok);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		pack();
		setResizable(false);
		setLocationRelativeTo(getOwner());
	}

	private static JLabel hint(String text) {
		var label = new JLabel("<html><div style='width:280px'>" + text + "</div></html>");
		label.setEnabled(false);
		return label;
	}

	private static int addRow(JPanel form, int row, JLabel label, JComponent field) {
		var c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		if (label != null) {
			c.gridx = 0;
			form.add(label, c);
		}
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(field, c);
		return row + 1;
	}

	private void onAuthChanged() {
		boolean auth = authBox.isSelected();
		usernameField.setEnabled(auth);
		secretField.setEnabled(auth);
		rememberBox.setEnabled(auth);
	}

	/** A pasted {@code redis://user:pass@host:6380} fills the form; the password never stays in the host. */
	private void absorbUri() {
		RedisUri uri = RedisUri.parse(hostField.getText());
		if (uri == null) {
			return;
		}
		hostField.setText(uri.host());
		portField.setText(String.valueOf(uri.port()));
		tlsBox.setSelected(uri.tls());
		if (uri.username() != null || uri.password() != null) {
			authBox.setSelected(true);
			usernameField.setText(uri.username() == null ? "" : uri.username());
			if (uri.password() != null) {
				secretField.setText(uri.password());
			}
		}
		if (nameField.getText().isBlank()) {
			nameField.setText(uri.host());
		}
		onAuthChanged();
		statusArea.setText(tr("editor.uri.absorbed"));
	}

	private RedisConnectionProfile profileFromForm() {
		return RedisConnectionProfile.builder()
				.id(idBeingEdited)
				.name(nameField.getText().trim())
				.host(hostField.getText().trim())
				.port(Integer.parseInt(portField.getText().trim()))
				.auth(authBox.isSelected())
				.username(authBox.isSelected() ? blankToNull(usernameField.getText()) : null)
				.tls(tlsBox.isSelected())
				.separator(separatorField.getText().isEmpty() ? RedisConnectionProfile.DEFAULT_SEPARATOR
						: separatorField.getText())
				.readOnly(readOnlyBox.isSelected())
				.build();
	}

	private String validateForm() {
		if (nameField.getText().isBlank()) {
			return tr("editor.error.name");
		}
		if (hostField.getText().isBlank()) {
			return tr("editor.error.host");
		}
		try {
			int port = Integer.parseInt(portField.getText().trim());
			if (port <= 0 || port > 65535) {
				return tr("editor.error.port");
			}
		} catch (NumberFormatException e) {
			return tr("editor.error.port");
		}
		return null;
	}

	private String typedSecret() {
		if (!authBox.isSelected()) {
			return null;
		}
		String typed = new String(secretField.getPassword());
		return typed.isEmpty() ? null : typed;
	}

	private void runTest() {
		absorbUri();
		String problem = validateForm();
		if (problem != null) {
			statusArea.setText(problem);
			return;
		}
		RedisConnectionProfile profile = profileFromForm();
		String secret = typedSecret();
		testButton.setEnabled(false);
		statusArea.setText(tr("editor.testing"));
		Thread.ofVirtual().name("redis-test-connection").start(() -> {
			String text;
			try {
				text = tester.test(profile, secret);
			} catch (Exception e) {
				text = tr("editor.test.failed", e.getMessage());
			}
			String shown = text;
			SwingUtilities.invokeLater(() -> {
				statusArea.setText(shown);
				statusArea.setCaretPosition(0);
				testButton.setEnabled(true);
			});
		});
	}

	private void onOk() {
		absorbUri();
		String problem = validateForm();
		if (problem != null) {
			statusArea.setText(problem);
			return;
		}
		RedisConnectionProfile profile = profileFromForm();
		result = new Result(profile, typedSecret(), profile.isAuth() && rememberBox.isSelected());
		dispose();
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}
