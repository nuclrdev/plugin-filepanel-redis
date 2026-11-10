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
package dev.nuclr.plugin.core.panel.redis.quickview;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import dev.nuclr.plugin.core.panel.redis.client.ErrorText;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Entry;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Field;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Scored;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.SetValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StreamValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;
import dev.nuclr.plugin.core.panel.redis.keys.KeyNames;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;
import dev.nuclr.plugin.core.panel.redis.value.ValueCodec;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick View of one key: the text of a string (JSON laid out, binary as a hex dump), or a
 * table of a hash's fields, a list's items, a set's members, a sorted set's members with
 * scores, a stream's newest entries. Reads are bounded: this is a peek, not an export.
 */
@Slf4j
public final class ValuePanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Elements read for a table. */
	static final int ITEM_CAP = 1_000;
	/** Bytes of a string read for the text view. */
	static final int BYTE_CAP = 1024 * 1024;
	/** Bytes of a binary string shown as a hex dump. */
	static final int HEX_CAP = 64 * 1024;

	private static final String CardText = "text";
	private static final String CardTable = "table";
	private static final DateTimeFormatter STREAM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
			.withZone(ZoneId.systemDefault());

	private final JLabel header = new JLabel(" ");
	private final JLabel status = new JLabel(" ");
	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private final JTextArea text = new JTextArea();
	private final DefaultTableModel model = new DefaultTableModel() {
		private static final long serialVersionUID = 1L;

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}
	};
	private final JTable table = new JTable(model);
	/** Bumped on every load and clear, so a slow read for a key the user has left is dropped. */
	private final AtomicLong generation = new AtomicLong();

	public ValuePanel() {
		super(new BorderLayout());
		text.setEditable(false);
		text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
		table.setAutoCreateRowSorter(true);
		table.setFillsViewportHeight(true);
		body.add(new JScrollPane(text), CardText);
		body.add(new JScrollPane(table), CardTable);
		header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	/** Reads {@code key} off the event dispatch thread and shows it. Call on the EDT. */
	public void show(RedisResource key, AtomicBoolean cancelled) {
		long mine = generation.incrementAndGet();
		header.setText(headerText(key));
		status.setText(tr("qv.loading"));
		text.setText("");
		model.setRowCount(0);
		model.setColumnCount(0);
		Thread.ofVirtual().name("redis-quickview").start(() -> {
			try {
				RedisValue value = key.readValue(ITEM_CAP, BYTE_CAP);
				if (cancelled != null && cancelled.get()) {
					return;
				}
				Rendered rendered = render(value);
				SwingUtilities.invokeLater(() -> {
					if (generation.get() == mine) {
						apply(rendered);
					}
				});
			} catch (Exception e) {
				log.debug("Quick View of {} failed: {}", key.getFullPath(), e.getMessage());
				SwingUtilities.invokeLater(() -> {
					if (generation.get() == mine) {
						status.setText(tr("qv.failed", ErrorText.describe(e)));
					}
				});
			}
		});
	}

	public void clear() {
		generation.incrementAndGet();
		header.setText(" ");
		status.setText(" ");
		text.setText("");
		model.setRowCount(0);
		model.setColumnCount(0);
	}

	private static String headerText(RedisResource key) {
		String type = key.getKeyType() == null ? "" : key.getMetadata().getOrDefault("Type", "").toString();
		String ttl = RedisResource.ttlText(key.getTtlMillis());
		var parts = new ArrayList<String>();
		parts.add(type);
		if (!ttl.isEmpty()) {
			parts.add(tr("qv.ttl", ttl));
		}
		return "<html><b>" + escapeHtml(KeyNames.display(key.getBytes())) + "</b>&nbsp;&nbsp;"
				+ escapeHtml(String.join("  ", parts)) + "</html>";
	}

	private static String escapeHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** What to show for a value, computed off the EDT. Package-private for tests. */
	record Rendered(String text, List<String> columns, List<Object[]> rows, String status) {

		boolean isText() {
			return text != null;
		}
	}

	static Rendered render(RedisValue value) {
		String count = String.format(Locale.ROOT, "%,d", value.read());
		String total = String.format(Locale.ROOT, "%,d", value.total());
		String more = value.truncated() ? tr("qv.truncated", count, total) : "";
		return switch (value) {
			case StringValue s -> renderString(s);
			case HashValue h -> {
				var rows = new ArrayList<Object[]>();
				for (Field f : h.fields()) {
					rows.add(new Object[] { KeyNames.display(f.name()), KeyNames.display(f.value()) });
				}
				yield new Rendered(null, List.of(tr("qv.col.field"), tr("qv.col.value")), rows,
						more.isEmpty() ? RedisResource.sizeText(value.type(), value.total()) : more);
			}
			case ListValue l -> {
				var rows = new ArrayList<Object[]>();
				for (int i = 0; i < l.items().size(); i++) {
					rows.add(new Object[] { i, KeyNames.display(l.items().get(i)) });
				}
				yield new Rendered(null, List.of(tr("qv.col.index"), tr("qv.col.value")), rows,
						more.isEmpty() ? RedisResource.sizeText(value.type(), value.total()) : more);
			}
			case SetValue s -> {
				var rows = new ArrayList<Object[]>();
				for (byte[] member : s.members()) {
					rows.add(new Object[] { KeyNames.display(member) });
				}
				yield new Rendered(null, List.of(tr("qv.col.member")), rows,
						more.isEmpty() ? RedisResource.sizeText(value.type(), value.total()) : more);
			}
			case ZSetValue z -> {
				var rows = new ArrayList<Object[]>();
				for (Scored member : z.members()) {
					rows.add(new Object[] { KeyNames.display(member.member()), member.score() });
				}
				yield new Rendered(null, List.of(tr("qv.col.member"), tr("qv.col.score")), rows,
						more.isEmpty() ? RedisResource.sizeText(value.type(), value.total()) : more);
			}
			case StreamValue s -> {
				var rows = new ArrayList<Object[]>();
				for (Entry entry : s.entries()) {
					var fields = new StringBuilder();
					for (Field f : entry.fields()) {
						if (!fields.isEmpty()) {
							fields.append(", ");
						}
						fields.append(KeyNames.display(f.name())).append('=').append(KeyNames.display(f.value()));
					}
					rows.add(new Object[] { entry.id(), streamTime(entry.id()), fields.toString() });
				}
				yield new Rendered(null, List.of(tr("qv.col.id"), tr("qv.col.time"), tr("qv.col.fields")), rows,
						more.isEmpty() ? RedisResource.sizeText(value.type(), value.total())
								: tr("qv.stream.newest", count, total));
			}
			default -> new Rendered(tr("qv.unreadable"), null, null, "");
		};
	}

	private static Rendered renderString(StringValue s) {
		String size = RedisResource.humanSize(s.total());
		byte[] bytes = s.truncated() ? wholeCharacters(s.bytes()) : s.bytes();
		String status = s.truncated() ? tr("qv.string.truncated", RedisResource.humanSize(bytes.length), size) : size;
		if (ValueCodec.isText(bytes)) {
			String text = KeyNames.utf8OrNull(bytes);
			String pretty = s.truncated() ? null : ValueCodec.prettyJsonOrNull(text);
			return new Rendered(pretty != null ? pretty : text, null, null,
					pretty != null ? status + "  " + tr("qv.json") : status);
		}
		return new Rendered(ValueCodec.hexDump(s.bytes(), HEX_CAP), null, null,
				status + "  " + (s.bytes().length > HEX_CAP ? tr("qv.binary.first", RedisResource.humanSize(HEX_CAP))
						: tr("qv.binary")));
	}

	/** A read cut short may end inside a UTF-8 character; drop that partial character. */
	static byte[] wholeCharacters(byte[] bytes) {
		for (int drop = 0; drop <= 3 && drop < bytes.length; drop++) {
			byte[] candidate = Arrays.copyOf(bytes, bytes.length - drop);
			if (KeyNames.utf8OrNull(candidate) != null) {
				return candidate;
			}
		}
		return bytes;
	}

	/** A stream id is {@code <milliseconds>-<sequence>}: the time the entry was added. */
	static String streamTime(String id) {
		int dash = id.indexOf('-');
		try {
			long millis = Long.parseLong(dash < 0 ? id : id.substring(0, dash));
			return STREAM_TIME.format(Instant.ofEpochMilli(millis));
		} catch (NumberFormatException e) {
			return "";
		}
	}

	private void apply(Rendered rendered) {
		if (rendered.isText()) {
			text.setText(rendered.text());
			text.setCaretPosition(0);
			cards.show(body, CardText);
		} else {
			model.setColumnIdentifiers(rendered.columns().toArray());
			for (Object[] row : rendered.rows()) {
				model.addRow(row);
			}
			cards.show(body, CardTable);
		}
		status.setText(rendered.status().isEmpty() ? " " : rendered.status());
	}

	/** Test seam: the status line as the user reads it. */
	String statusText() {
		return status.getText();
	}
}
