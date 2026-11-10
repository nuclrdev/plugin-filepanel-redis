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
package dev.nuclr.plugin.core.panel.redis;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.redis.client.ErrorText;
import dev.nuclr.plugin.core.panel.redis.client.KeyInfo;
import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.redis.connection.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.redis.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.edit.ValueEditService;
import dev.nuclr.plugin.core.panel.redis.keys.KeyNames;
import dev.nuclr.plugin.core.panel.redis.keys.KeyTree;
import dev.nuclr.plugin.core.panel.redis.panel.ConnectionEditorDialog;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;
import dev.nuclr.plugin.core.panel.redis.resource.ResourceKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Browses Redis servers as a virtual filesystem:
 * {@code Explorer root -> Connection -> db0..dbN -> key folders -> keys}. Keys are grouped
 * into folders by the connection's separator ({@code :} unless set otherwise).
 *
 * <p>Ctrl+Q shows a key's value through the bundled {@link RedisQuickViewPlugin}; F4 edits
 * it in Commander's editor ({@link ValueEditService}); F8 deletes keys and whole folders.
 * A connection saved read-only offers neither.
 */
@Slf4j
public final class RedisFilePanelPlugin implements FilePanelNuclrPlugin {

	/** Keys a single listing scans at most. Beyond it the folder says it was cut short. */
	static final int SCAN_CAP = 50_000;

	/** Keys described per pipelined round trip while a listing streams in. */
	static final int DESCRIBE_BATCH = 1_000;

	/** Keys per UNLINK when deleting a folder. */
	static final int DELETE_BATCH = 1_000;

	private static final String ActionNewConnection = "redis.newConnection";
	private static final String ActionEditConnection = "redis.editConnection";
	private static final String ActionDeleteConnection = "redis.deleteConnection";
	private static final String ActionDisconnect = "redis.disconnect";
	private static final String ActionCopyKeyName = "redis.copyKeyName";
	private static final String ActionEdit = "filepanel.edit";
	private static final String ActionDelete = "filepanel.delete";
	private static final String ActionDeletePermanent = "filepanel.deletePermanent";
	/** Must match Commander's F5 action protocol value (see {@code LocalFileSystemPlugin}). */
	private static final String ActionCopy = "filepanel.copy";
	private static final String ActionAcceptCopy = "accept.copy";
	private static final String EventRefresh = "refresh.plugin.file.panel";

	/** Event-type prefix the commander parses into one of its own sort comparators. */
	private static final String SortEventPrefix = "filepanel.sort:";

	private static final List<String> KEY_COLUMNS = List.of("Name", "Type", "Size", "TTL");

	/** The user dismissed the password prompt; the listing simply comes back empty. */
	private static final class ConnectCancelled extends Exception {

		private static final long serialVersionUID = 1L;

		ConnectCancelled() {
			super("cancelled", null, false, false);
		}
	}

	record SecretPrompt(String secret, boolean remember) {
	}

	private final String uuid = UUID.randomUUID().toString();
	private final ConnectionRegistry.SessionFactory sessionFactory;

	private NuclrPluginContext context;
	private ConnectionProfileStore profileStore;
	private CredentialStore credentialStore;
	private ConnectionRegistry connectionRegistry;
	private ValueEditService editService;

	private volatile boolean focused;
	private volatile RedisResource currentResource;

	/** Keys one listing scans; a field so tests can reach the limit without 50,000 keys. */
	int scanCap = SCAN_CAP;

	/** Asks for a password; {@code null} when dismissed. A seam so tests can type one. */
	Function<String, SecretPrompt> secretPrompter = RedisFilePanelPlugin::promptForSecret;

	/** Connections whose stored password the server refused: the next connect asks rather than resend it. */
	private final Set<String> refusedStored = ConcurrentHashMap.newKeySet();

	public RedisFilePanelPlugin() {
		this(null);
	}

	/** For tests: every connection is made through {@code sessionFactory}. */
	RedisFilePanelPlugin(ConnectionRegistry.SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	@Override
	public void preinit(NuclrPluginContext context) {
		Messages.use(context.getLocale());
		this.context = context;
		this.profileStore = new ConnectionProfileStore(context.getSettings());
		this.credentialStore = new CredentialStore(context.getCredentialStore());
		this.connectionRegistry = sessionFactory == null ? new ConnectionRegistry()
				: new ConnectionRegistry(sessionFactory);
		this.editService = new ValueEditService(this::writeKey, new ValueEditService.Prompts() {
			@Override
			public boolean confirmOverwrite(String keyName) {
				return confirm(tr("edit.conflict.title"), tr("edit.conflict", keyName));
			}

			@Override
			public void error(String title, String message) {
				showErrorDialog(title, message);
			}
		});
		if (context.getEventBus() != null) {
			context.getEventBus().subscribe(editService);
		}
		this.currentResource = RedisResource.explorerRoot();
		log.info("Redis Explorer panel plugin loaded");
	}

	@Override
	public void init() {
	}

	@Override
	public void unload() {
		editService.stopAll();
		if (context != null && context.getEventBus() != null) {
			context.getEventBus().unsubscribe(editService);
		}
		connectionRegistry.disconnectAll();
		log.info("Redis Explorer panel plugin unloaded");
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		// Every listing is fetched whole in openResource; nothing stays open per resource.
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return resource instanceof RedisResource;
	}

	// ------------------------------------------------------------------
	// Alt+F1 / Alt+F2 drive picker
	// ------------------------------------------------------------------

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var item = new MenuItem();
		item.setText("Redis Explorer");
		item.setPath(RedisResource.explorerRoot());
		item.setUuid(PluginIds.PLUGIN_ID + ":explorer");

		var holder = new MenuItemsHolder();
		holder.setTitle("Redis");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {
		if (!(resourceToOpen instanceof RedisResource redis)) {
			return null;
		}
		try {
			return switch (redis.getKind()) {
				case EXPLORER_ROOT -> listConnections(sink);
				case CONNECTION -> listDatabases(redis, sink);
				case DATABASE, FOLDER -> listKeys(redis, cancelled, sink);
				case KEY, LIMIT_MARKER -> null;
			};
		} catch (ConnectCancelled cancelledByUser) {
			return new NuclrResourceData();
		} catch (Exception e) {
			log.error("Failed to open Redis resource {}: {}", redis.getFullPath(), e.getMessage(), e);
			if (ErrorText.isAuthenticationFailure(e) && redis.getProfileId() != null) {
				connectionRegistry.disconnect(redis.getProfileId());
			}
			showErrorDialog(tr("panel.title"), tr("error.open", redis.getName(), ErrorText.describe(e)));
			return new NuclrResourceData();
		}
	}

	private static NuclrResourceData listing(List<String> columns, EntrySink sink) {
		var data = new NuclrResourceData();
		data.getColumnNames().addAll(columns);
		if (sink != null) {
			sink.columns(columns);
		}
		return data;
	}

	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource entry) {
		data.getEntries().add(entry);
		if (sink != null) {
			sink.add(entry);
		}
	}

	private NuclrResourceData listConnections(EntrySink sink) {
		var data = listing(List.of("Name", "Status", "Address"), sink);
		for (RedisConnectionProfile profile : profileStore.loadAll()) {
			var entry = RedisResource.connection(profile);
			entry.getMetadata().put("Status", connectionRegistry.isConnected(profile.getId()) ? tr("status.connected") : "");
			add(data, sink, entry);
		}
		currentResource = RedisResource.explorerRoot();
		return data;
	}

	private NuclrResourceData listDatabases(RedisResource connectionRes, EntrySink sink) throws Exception {
		String profileId = connectionRes.getProfileId();
		RedisConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			return new NuclrResourceData();
		}
		RedisSession session = ensureConnected(profileId);
		int databases = session.databaseCount();
		long[] keys = session.keyCounts(databases);
		var data = listing(List.of("Name", "Keys"), sink);
		add(data, sink, RedisResource.parentEntry(RedisResource.explorerRoot()));
		for (int db = 0; db < databases; db++) {
			add(data, sink, RedisResource.database(profileId, db, profile.isReadOnly(), keys[db]));
		}
		currentResource = RedisResource.connection(profile);
		return data;
	}

	private NuclrResourceData listKeys(RedisResource folderRes, AtomicBoolean cancelled, EntrySink sink) throws Exception {
		String profileId = folderRes.getProfileId();
		RedisConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			return new NuclrResourceData();
		}
		RedisSession session = ensureConnected(profileId);
		int db = folderRes.getDb();
		boolean readOnly = profile.isReadOnly();
		byte[] separator = KeyNames.bytes(profile.getEffectiveSeparator());
		byte[] prefix = folderRes.getKind() == ResourceKind.DATABASE ? new byte[0] : folderRes.getBytes();

		KeyTree.Level level = KeyTree.scan(session, db, prefix, separator, scanCap, cancelled, null);
		if (level.cancelled()) {
			return new NuclrResourceData();
		}
		var data = listing(KEY_COLUMNS, sink);
		add(data, sink, RedisResource.parentEntry(parentOf(profile, folderRes, separator)));
		for (KeyTree.Folder folder : level.folders()) {
			add(data, sink, RedisResource.folder(profileId, db, readOnly, folder, level.capped()));
		}
		List<byte[]> keys = level.keys();
		for (int from = 0; from < keys.size(); from += DESCRIBE_BATCH) {
			if (isCancelled(cancelled)) {
				return new NuclrResourceData();
			}
			for (KeyInfo info : session.describe(db, keys.subList(from, Math.min(keys.size(), from + DESCRIBE_BATCH)))) {
				if (info.type() == KeyType.NONE) {
					continue; // expired or deleted since the scan
				}
				add(data, sink, RedisResource.key(profileId, db, readOnly, prefix, info).withReader(this::readKey));
			}
		}
		if (level.capped()) {
			add(data, sink, RedisResource.limitMarker(profileId, db, prefix,
					tr("keys.limit", String.format(Locale.ROOT, "%,d", level.scanned()))));
		}
		currentResource = folderRes.getKind() == ResourceKind.DATABASE
				? RedisResource.database(profileId, db, readOnly)
				: folderAt(profile, db, prefix, separator);
		return data;
	}

	/** What {@code ..} leads to: the folder one separator up, the database, or the connection. */
	static RedisResource parentOf(RedisConnectionProfile profile, RedisResource res, byte[] separator) {
		int db = res.getDb();
		if (res.getKind() == ResourceKind.DATABASE) {
			return RedisResource.connection(profile);
		}
		byte[] prefix = res.getBytes();
		// Before this folder's own trailing separator, the one that ends its parent.
		int at = lastSeparatorBefore(prefix, separator, prefix.length - separator.length);
		if (at < 0) {
			return RedisResource.database(profile.getId(), db, profile.isReadOnly());
		}
		return folderAt(profile, db, Arrays.copyOf(prefix, at + separator.length), separator);
	}

	/** The folder whose keys start with {@code prefix}, which ends with the separator. */
	static RedisResource folderAt(RedisConnectionProfile profile, int db, byte[] prefix, byte[] separator) {
		int end = prefix.length - separator.length;
		int start = lastSeparatorBefore(prefix, separator, end);
		int from = start < 0 ? 0 : start + separator.length;
		String segment = end < from ? "" : KeyNames.display(Arrays.copyOfRange(prefix, from, end));
		return RedisResource.folder(profile.getId(), db, profile.isReadOnly(), prefix, segment);
	}

	private static int lastSeparatorBefore(byte[] bytes, byte[] separator, int end) {
		int at = -1;
		for (int i = KeyNames.indexOf(bytes, separator, 0); i >= 0 && i < end; i = KeyNames.indexOf(bytes, separator, i + 1)) {
			at = i;
		}
		return at;
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	/** The value of a key row, for Quick View, F4 and F5. */
	RedisValue readKey(RedisResource key, int itemCap, int byteCap) throws Exception {
		return ensureConnected(key.getProfileId()).read(key.getDb(), key.getBytes(), itemCap, byteCap);
	}

	private RedisSession.WriteResult writeKey(RedisResource key, RedisValue expected, RedisValue value, boolean force)
			throws Exception {
		return ensureConnected(key.getProfileId()).replace(key.getDb(), key.getBytes(), expected, value, force);
	}

	// ------------------------------------------------------------------
	// Connecting
	// ------------------------------------------------------------------

	/** The session for {@code profileId}, connecting on first use and asking for a missing password. */
	private RedisSession ensureConnected(String profileId) throws Exception {
		RedisSession existing = connectionRegistry.get(profileId);
		if (existing != null) {
			return existing;
		}
		// One first use at a time per connection: a second caller waits here and then
		// finds the session, rather than asking for the password again or connecting twice.
		synchronized (connectionRegistry.lockFor(profileId)) {
			return connectOnce(profileId);
		}
	}

	private RedisSession connectOnce(String profileId) throws Exception {
		RedisSession existing = connectionRegistry.get(profileId);
		if (existing != null) {
			return existing;
		}
		RedisConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			throw new IllegalStateException("Unknown connection: " + profileId);
		}
		boolean refused = refusedStored.contains(profileId);
		String secret = profile.needsSecret() && !refused ? credentialStore.loadPassword(profileId) : null;
		boolean fromStore = secret != null;
		SecretPrompt typed = null;
		if (secret == null && (profile.needsSecret() || refused)) {
			typed = askSecret(tr(refused ? "secret.prompt.refused" : "secret.prompt", profile.getName()));
			secret = typed.secret();
		}
		RedisSession session;
		try {
			session = connectionRegistry.connect(profile, secret);
		} catch (Exception e) {
			if (!ErrorText.isAuthenticationFailure(e) || typed != null) {
				throw e; // not about the password, or the one just typed was wrong: say so
			}
			// The stored password was refused, or the server wants one the connection did
			// not know about. Ask now rather than fail; and until a connect succeeds, never
			// send the refused one again.
			if (fromStore) {
				refusedStored.add(profileId);
			}
			typed = askSecret(tr(fromStore ? "secret.prompt.refused" : "secret.prompt.required", profile.getName()));
			secret = typed.secret();
			session = connectionRegistry.connect(profile, secret);
		}
		refusedStored.remove(profileId);
		if (typed != null && secret != null) {
			if (!profile.isAuth()) {
				profile.setAuth(true);
				profileStore.update(profile);
			}
			if (typed.remember()) {
				warnIfNotStored(credentialStore.savePassword(profileId, secret.toCharArray()));
			} else {
				// A stored password is now either refused or not the one in use: either way it
				// must not be sent on the next connect, so it goes even though the new one stays unsaved.
				credentialStore.deletePassword(profileId);
			}
		}
		return session;
	}

	/** The password the user types, or {@link ConnectCancelled} when they dismiss the prompt. */
	private SecretPrompt askSecret(String message) throws ConnectCancelled {
		SecretPrompt prompt = secretPrompter.apply(message);
		if (prompt == null) {
			throw new ConnectCancelled();
		}
		return prompt;
	}

	// ------------------------------------------------------------------
	// Location / selection text
	// ------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		RedisResource r = currentResource;
		if (r == null || r.getKind() == ResourceKind.EXPLORER_ROOT) {
			return tr("panel.title");
		}
		String connection = connectionDisplayName(r.getProfileId());
		return switch (r.getKind()) {
			case CONNECTION -> connection;
			case DATABASE -> connection + " / db" + r.getDb();
			case FOLDER -> connection + " / db" + r.getDb() + " / " + KeyNames.display(r.getBytes());
			default -> r.getFullPath();
		};
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return "";
		}
		long keys = 0;
		long folders = 0;
		for (NuclrResource r : selectedResources) {
			if (r instanceof RedisResource redis && redis.getKind() == ResourceKind.FOLDER) {
				folders++;
			} else if (r instanceof RedisResource redis && redis.isKey()) {
				keys++;
			}
		}
		if (folders == 0 && keys == selectedResources.size()) {
			return keys == 1 ? tr("summary.keys.one") : tr("summary.keys.many", String.valueOf(keys));
		}
		return selectedResources.size() == 1 ? tr("summary.items.one")
				: tr("summary.items.many", String.valueOf(selectedResources.size()));
	}

	private String connectionDisplayName(String profileId) {
		RedisConnectionProfile profile = profileStore.findById(profileId);
		return profile != null ? profile.getName() : String.valueOf(profileId);
	}

	// ------------------------------------------------------------------
	// Menus (F-key bar + right-click)
	// ------------------------------------------------------------------

	/** The resource under the cursor, or where the panel stands when there is none (an empty folder). */
	private RedisResource menuTarget(NuclrResource resource) {
		return resource instanceof RedisResource redis ? redis : currentResource;
	}

	private static boolean isConnectionFocused(NuclrResource resource) {
		return resource instanceof RedisResource redis && redis.getKind() == ResourceKind.CONNECTION;
	}

	/** A key or a folder the cursor is on, and not the {@code ..} row. */
	private static boolean isDeletable(NuclrResource resource) {
		return resource instanceof RedisResource redis && !"..".equals(redis.getName())
				&& (redis.isKey() || redis.getKind() == ResourceKind.FOLDER) && !redis.isReadOnly();
	}

	private static boolean isEditable(NuclrResource resource) {
		return resource instanceof RedisResource redis && redis.isKey() && !redis.isReadOnly()
				&& redis.getKeyType() != null && redis.getKeyType().editable();
	}

	private static NuclrMenuResource sort(String label, String functionKey, String criterion, String columnLabel) {
		return new NuclrMenuResource(label, functionKey,
				SortEventPrefix + criterion + (columnLabel == null ? "" : ":" + columnLabel));
	}

	private void addSortItems(List<NuclrMenuResource> items) {
		RedisResource here = currentResource;
		if (here == null) {
			return;
		}
		items.add(sort(tr("sort.name"), "Ctrl+F3", "name", "Name"));
		if (here.getKind() == ResourceKind.DATABASE || here.getKind() == ResourceKind.FOLDER) {
			items.add(sort(tr("sort.size"), "Ctrl+F6", "size", "Size"));
		}
		items.add(sort(tr("sort.unsort"), "Ctrl+F7", "unsorted", null));
		items.add(sort(tr("sort.dialog"), "Ctrl+F12", "dialog", null));
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		var items = new ArrayList<NuclrMenuResource>();
		addSortItems(items);
		RedisResource target = menuTarget(resource);
		if (target == null) {
			return items;
		}
		switch (target.getKind()) {
			case EXPLORER_ROOT, CONNECTION -> {
				items.add(new NuclrMenuResource(tr("menu.new.connection"), "F7", ActionNewConnection));
				if (isConnectionFocused(resource)) {
					items.add(new NuclrMenuResource(tr("menu.edit.connection"), "F4", ActionEditConnection));
					items.add(new NuclrMenuResource(tr("menu.delete.connection"), "F8", ActionDeleteConnection));
				}
			}
			case KEY, FOLDER, DATABASE -> {
				if (isEditable(resource)) {
					items.add(new NuclrMenuResource(tr("menu.edit"), "F4", ActionEdit));
				}
				if (resource instanceof RedisResource redis && redis.isKey()) {
					items.add(new NuclrMenuResource(tr("menu.copy"), "F5", ActionCopy));
				}
				if (isDeletable(resource)) {
					items.add(new NuclrMenuResource(tr("menu.delete"), "F8", ActionDelete));
				}
			}
			default -> {
				// The limit marker is a note, not a key.
			}
		}
		return items;
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource, List<NuclrResource> selectedResources) {
		RedisResource target = menuTarget(focusedResource);
		if (target == null) {
			return List.of();
		}
		var newConnection = NuclrContextMenuItem.builder().label(tr("context.new.connection")).actionType(ActionNewConnection).build();
		return switch (target.getKind()) {
			case EXPLORER_ROOT -> List.of(newConnection);
			case CONNECTION -> {
				if (!isConnectionFocused(focusedResource)) {
					yield List.of(newConnection);
				}
				String profileId = ((RedisResource) focusedResource).getProfileId();
				yield List.of(
						newConnection,
						NuclrContextMenuItem.builder().label(tr("context.edit.connection")).actionType(ActionEditConnection).build(),
						NuclrContextMenuItem.builder().label(tr("context.delete.connection")).actionType(ActionDeleteConnection)
								.destructive(true).build(),
						NuclrContextMenuItem.separator(),
						NuclrContextMenuItem.builder().label(tr("context.disconnect")).actionType(ActionDisconnect)
								.enabled(connectionRegistry.isConnected(profileId)).build());
			}
			case KEY, FOLDER -> {
				if (!(focusedResource instanceof RedisResource redis) || "..".equals(redis.getName())) {
					yield List.of();
				}
				var items = new ArrayList<NuclrContextMenuItem>();
				if (redis.isKey()) {
					items.add(NuclrContextMenuItem.builder().label(tr("context.edit")).actionType(ActionEdit)
							.enabled(isEditable(redis)).build());
				}
				items.add(NuclrContextMenuItem.builder().label(tr("context.copy.key.name")).actionType(ActionCopyKeyName).build());
				items.add(NuclrContextMenuItem.separator());
				items.add(NuclrContextMenuItem.builder().label(tr("context.delete")).actionType(ActionDelete)
						.destructive(true).enabled(isDeletable(redis)).build());
				yield items;
			}
			default -> List.of();
		};
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		switch (actionType) {
			case ActionNewConnection -> handleNewConnection(data);
			case ActionEditConnection -> handleEditConnection(focusedResource, data);
			case ActionDeleteConnection -> handleDeleteConnection(focusedResource, selectedResources, data);
			case ActionDisconnect -> handleDisconnect(focusedResource, data);
			case ActionEdit -> handleEdit(focusedResource);
			case ActionDelete, ActionDeletePermanent -> handleDelete(focusedResource, selectedResources, data);
			case ActionCopy -> handleCopy(other, selectedResources, focusedResource, data, callback);
			case ActionCopyKeyName -> handleCopyKeyName(focusedResource);
			default -> {
				// Nothing else is offered, so nothing else should arrive.
			}
		}
	}

	private void handleCopy(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback) {
		if (other == null || other.uuid().equals(this.uuid) || other instanceof QuickViewNuclrPlugin) {
			return; // no copy target inside a Redis panel
		}
		List<NuclrResource> keys = keysOf(selectedResources, focusedResource);
		if (keys.isEmpty()) {
			return;
		}
		other.act(null, ActionAcceptCopy, keys, keys.contains(focusedResource) ? focusedResource : keys.get(0), data,
				callback);
	}

	/** Key rows among the selection, or the focused one when nothing is selected. */
	private static List<NuclrResource> keysOf(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		var keys = new ArrayList<NuclrResource>();
		for (NuclrResource r : targets(selectedResources, focusedResource)) {
			if (r instanceof RedisResource redis && redis.isKey()) {
				keys.add(r);
			}
		}
		return keys;
	}

	private static List<NuclrResource> targets(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		if (selectedResources != null && !selectedResources.isEmpty()) {
			return selectedResources;
		}
		return focusedResource != null ? List.of(focusedResource) : List.of();
	}

	private void handleCopyKeyName(NuclrResource focusedResource) {
		if (!(focusedResource instanceof RedisResource redis) || GraphicsEnvironment.isHeadless()) {
			return;
		}
		String text = KeyNames.display(redis.getBytes());
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private void handleEdit(NuclrResource focusedResource) {
		if (!(focusedResource instanceof RedisResource redis) || !redis.isKey()) {
			return;
		}
		if (redis.isReadOnly()) {
			showInfoDialog(tr("edit.title"), tr("read.only.notice"));
			return;
		}
		if (redis.getKeyType() == null || !redis.getKeyType().editable()) {
			showInfoDialog(tr("edit.title"), redis.getKeyType() == KeyType.STREAM ? tr("edit.not.stream")
					: tr("edit.not.type", String.valueOf(redis.getTypeName())));
			return;
		}
		// Reading the value is a network round trip: keep it off the event dispatch thread.
		Thread.ofVirtual().name("redis-edit-open").start(() -> editService.edit(redis, context.getEventBus()));
	}

	/**
	 * What F8 removes: keys, and folders with every key under them. {@code folderKeys} is
	 * what the listing counted; {@code folderKeysPartial} when a listing stopped at the
	 * scan limit, so the folders may hold many more.
	 */
	record DeleteTargets(String profileId, int db, List<byte[]> keys, List<RedisResource> folders, long folderKeys,
			boolean folderKeysPartial) {

		boolean isEmpty() {
			return keys.isEmpty() && folders.isEmpty();
		}
	}

	static DeleteTargets deleteTargets(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		String profileId = null;
		int db = -1;
		var keys = new ArrayList<byte[]>();
		var folders = new ArrayList<RedisResource>();
		long folderKeys = 0;
		boolean partial = false;
		for (NuclrResource r : targets(selectedResources, focusedResource)) {
			if (!(r instanceof RedisResource redis) || "..".equals(redis.getName()) || redis.isReadOnly()) {
				continue;
			}
			if (profileId != null && (!profileId.equals(redis.getProfileId()) || db != redis.getDb())) {
				continue; // a listing is one database; anything else is not from it
			}
			if (redis.isKey()) {
				keys.add(redis.getBytes());
			} else if (redis.getKind() == ResourceKind.FOLDER && redis.getBytes().length > 0) {
				folders.add(redis);
				folderKeys += redis.getLength();
				partial |= redis.isCountPartial();
			} else {
				continue;
			}
			profileId = redis.getProfileId();
			db = redis.getDb();
		}
		return new DeleteTargets(profileId, db, keys, folders, folderKeys, partial);
	}

	private void handleDelete(NuclrResource focusedResource, List<NuclrResource> selectedResources, Map<String, Object> data) {
		RedisResource here = currentResource;
		if (here != null && here.isReadOnly()) {
			showInfoDialog(tr("delete.title"), tr("read.only.notice"));
			return;
		}
		DeleteTargets targets = deleteTargets(selectedResources, focusedResource);
		if (targets.isEmpty()) {
			return;
		}
		if (!confirm(tr("delete.title"), deleteQuestion(targets, focusedResource))) {
			return;
		}
		if (targets.folders().isEmpty()) {
			// A handful of keys: done before Commander reloads the listing.
			try {
				deleteKeys(targets, null);
			} catch (Exception e) {
				showErrorDialog(tr("delete.title"), tr("delete.failed", ErrorText.describe(e)));
			}
			putRefresh(data);
			return;
		}
		// A folder may hold any number of keys: scan and unlink in the background.
		Thread.ofVirtual().name("redis-delete").start(() -> {
			try {
				deleteKeys(targets, null);
			} catch (Exception e) {
				log.error("Deleting Redis keys failed: {}", e.getMessage(), e);
				showErrorDialog(tr("delete.title"), tr("delete.failed", ErrorText.describe(e)));
			}
			if (context != null && context.getEventBus() != null) {
				context.getEventBus().emit(EventRefresh, Map.of("plugin.uuid", uuid), null);
			}
		});
	}

	static String deleteQuestion(DeleteTargets targets, NuclrResource focusedResource) {
		if (targets.folders().isEmpty()) {
			if (targets.keys().size() == 1) {
				return tr("delete.confirm.key", KeyNames.display(targets.keys().get(0)));
			}
			return tr("delete.confirm.keys", String.valueOf(targets.keys().size()));
		}
		String keysUnder = String.format(Locale.ROOT, "%,d", targets.folderKeys());
		// A count from a listing cut short is a floor, never a total: say so rather than understate it.
		String suffix = targets.folderKeysPartial() ? ".partial" : "";
		if (targets.keys().isEmpty() && targets.folders().size() == 1) {
			return tr("delete.confirm.folder" + suffix, KeyNames.display(targets.folders().get(0).getBytes()), keysUnder);
		}
		return tr("delete.confirm.mixed" + suffix, String.valueOf(targets.keys().size()),
				String.valueOf(targets.folders().size()), keysUnder);
	}

	/**
	 * Unlinks the keys, then every key under each folder, one scan page at a time so a
	 * folder of any size never sits in memory whole.
	 *
	 * @return how many keys existed and were removed
	 */
	long deleteKeys(DeleteTargets targets, AtomicBoolean cancelled) throws Exception {
		RedisSession session = ensureConnected(targets.profileId());
		long deleted = 0;
		for (int from = 0; from < targets.keys().size(); from += DELETE_BATCH) {
			deleted += session.delete(targets.db(),
					targets.keys().subList(from, Math.min(targets.keys().size(), from + DELETE_BATCH)));
		}
		for (RedisResource folder : targets.folders()) {
			deleted += KeyTree.forEachPage(session, targets.db(), folder.getBytes(), cancelled, DELETE_BATCH,
					batch -> session.delete(targets.db(), batch));
		}
		return deleted;
	}

	// ------------------------------------------------------------------
	// Connection profiles
	// ------------------------------------------------------------------

	/** Test Connection in the editor: a ping through a session nobody keeps. A blank password on an edit means the stored one. */
	private String testConnection(RedisConnectionProfile profile, String typedSecret) throws Exception {
		String secret = typedSecret;
		if (secret == null && profile.needsSecret() && profile.getId() != null) {
			secret = credentialStore.loadPassword(profile.getId());
		}
		try (RedisSession session = connectionRegistry.open(profile, secret)) {
			RedisSession.ServerInfo info = session.ping();
			ConnectionRegistry.checkSupported(info);
			return tr("editor.test.ok", info.version().isBlank() ? "?" : info.version(),
					info.mode().isBlank() ? "standalone" : info.mode());
		} catch (Exception e) {
			throw new Exception(ErrorText.describe(e), e);
		}
	}

	private void handleNewConnection(Map<String, Object> data) {
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showNew(activeWindow(), this::testConnection);
		if (result == null) {
			return;
		}
		RedisConnectionProfile profile = profileStore.add(result.profile());
		storeSecret(profile.getId(), result);
		putRefresh(data);
	}

	private void handleEditConnection(NuclrResource focusedResource, Map<String, Object> data) {
		String profileId = connectionIdOf(focusedResource);
		RedisConnectionProfile existing = profileId == null ? null : profileStore.findById(profileId);
		if (existing == null) {
			return;
		}
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showEdit(activeWindow(), existing,
				credentialStore.hasPassword(profileId), this::testConnection);
		if (result == null) {
			return;
		}
		connectionRegistry.disconnect(profileId); // the address or the password may have changed
		refusedStored.remove(profileId);
		profileStore.update(result.profile());
		storeSecret(profileId, result);
		putRefresh(data);
	}

	/** A typed password replaces the stored one; an unticked Remember forgets it. */
	private void storeSecret(String profileId, ConnectionEditorDialog.Result result) {
		if (!result.rememberSecret()) {
			credentialStore.deletePassword(profileId);
		} else if (result.secret() != null) {
			warnIfNotStored(credentialStore.savePassword(profileId, result.secret().toCharArray()));
		}
	}

	private void handleDeleteConnection(NuclrResource focusedResource, List<NuclrResource> selectedResources,
			Map<String, Object> data) {
		var ids = new ArrayList<String>();
		for (NuclrResource r : targets(selectedResources, focusedResource)) {
			if (r instanceof RedisResource redis && redis.getKind() == ResourceKind.CONNECTION
					&& !ids.contains(redis.getProfileId())) {
				ids.add(redis.getProfileId());
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		if (!confirm(tr("delete.connection.title"),
				ids.size() == 1 ? tr("delete.connection.confirm.one")
						: tr("delete.connection.confirm.many", String.valueOf(ids.size())))) {
			return;
		}
		deleteConnections(ids);
		putRefresh(data);
	}

	/** Forget the connections: session, profile and stored password. Separate from the dialog for tests. */
	void deleteConnections(Iterable<String> ids) {
		for (String id : ids) {
			connectionRegistry.disconnect(id);
			refusedStored.remove(id);
			profileStore.delete(id);
			credentialStore.deletePassword(id);
		}
	}

	private void handleDisconnect(NuclrResource focusedResource, Map<String, Object> data) {
		String id = connectionIdOf(focusedResource);
		if (id != null) {
			connectionRegistry.disconnect(id);
			putRefresh(data);
		}
	}

	private static String connectionIdOf(NuclrResource resource) {
		return resource instanceof RedisResource redis ? redis.getProfileId() : null;
	}

	private static void putRefresh(Map<String, Object> data) {
		if (data == null) {
			return;
		}
		try {
			data.put("result.refresh", Boolean.TRUE);
		} catch (UnsupportedOperationException ignored) {
			// an immutable payload map; nothing more we can do to request a refresh
		}
	}

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	private static <T> T onEdt(Supplier<T> ask, T headless) {
		if (GraphicsEnvironment.isHeadless()) {
			return headless;
		}
		if (SwingUtilities.isEventDispatchThread()) {
			return ask.get();
		}
		var result = new AtomicReference<T>(headless);
		try {
			SwingUtilities.invokeAndWait(() -> result.set(ask.get()));
		} catch (Exception e) {
			return headless;
		}
		return result.get();
	}

	/** @return the entered password (blank becomes {@code null}), or {@code null} if cancelled. */
	private static SecretPrompt promptForSecret(String prompt) {
		return onEdt(() -> {
			var field = new JPasswordField(24);
			var rememberBox = new JCheckBox(tr("secret.remember"), true);
			var panel = new JPanel(new BorderLayout(6, 6));
			panel.add(new JLabel(prompt), BorderLayout.NORTH);
			panel.add(field, BorderLayout.CENTER);
			panel.add(rememberBox, BorderLayout.SOUTH);
			int choice = JOptionPane.showConfirmDialog(activeWindow(), panel, tr("secret.title"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			String typed = new String(field.getPassword());
			return new SecretPrompt(typed.isEmpty() ? null : typed, rememberBox.isSelected());
		}, null);
	}

	/** A Yes/No question; {@code false} without a display. */
	private static boolean confirm(String title, String question) {
		return onEdt(() -> JOptionPane.showConfirmDialog(activeWindow(), question, title, JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION, false);
	}

	private void warnIfNotStored(String failureReason) {
		if (failureReason != null) {
			showErrorDialog(tr("secret.not.stored.title"), tr("secret.not.stored", failureReason));
		}
	}

	private static void showErrorDialog(String title, String message) {
		showDialog(title, message, JOptionPane.ERROR_MESSAGE);
	}

	private static void showInfoDialog(String title, String message) {
		showDialog(title, message, JOptionPane.INFORMATION_MESSAGE);
	}

	private static void showDialog(String title, String message, int type) {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("{}: {}", title, message);
			return;
		}
		Runnable show = () -> JOptionPane.showMessageDialog(activeWindow(), message, title, type);
		if (SwingUtilities.isEventDispatchThread()) {
			show.run();
		} else {
			SwingUtilities.invokeLater(show);
		}
	}

	private static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}
}
