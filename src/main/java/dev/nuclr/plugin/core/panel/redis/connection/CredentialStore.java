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
package dev.nuclr.plugin.core.panel.redis.connection;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.util.Objects;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;
import lombok.extern.slf4j.Slf4j;

/**
 * A connection's password never goes through
 * {@link ConnectionProfileStore} / {@code NuclrSettings} or any plain config file. They
 * live only in the OS credential store (Windows Credential Manager, macOS Keychain, or
 * the Linux Secret Service), reached through {@link NuclrCredentialStore}, which the host
 * supplies already scoped to this plugin's id.
 *
 * <p>This is a thin adapter that turns {@link NuclrCredentialException} into an ordinary
 * return value, because every caller here wants the same degradation: a platform with no
 * usable backend simply behaves as "no stored secret", and {@code RedisFilePanelPlugin}
 * then asks for one on connect.
 *
 * <p>Operations may block, and on some platforms may pop an OS unlock prompt, so callers
 * should stay off the event dispatch thread where they reasonably can.
 */
@Slf4j
public final class CredentialStore {

	private final NuclrCredentialStore store;

	public CredentialStore(NuclrCredentialStore store) {
		this.store = Objects.requireNonNull(store, "credential store");
	}

	/**
	 * @return the stored password, or {@code null} when none is stored, the profile id is
	 *         unusable as a key, or OS storage is unavailable
	 */
	public String loadPassword(String profileId) {
		if (!isUsableKey(profileId)) {
			return null;
		}
		try {
			return store.get(profileId).orElse(null);
		} catch (NuclrCredentialException e) {
			log.debug("No stored password for connection {}: {}", profileId, e.getMessage());
			return null;
		}
	}

	/**
	 * Store (or replace) the password for {@code profileId}. A blank password stores
	 * nothing and clears any previous entry — the host rejects empty secrets, and
	 * "remember an empty password" only ever means "do not remember one".
	 *
	 * @return {@code null} on success, or a user-facing reason it could not be stored
	 */
	public String savePassword(String profileId, char[] password) {
		if (!isUsableKey(profileId)) {
			return tr("credentials.no.key");
		}
		String secret = password == null ? "" : new String(password);
		if (secret.isBlank()) {
			deletePassword(profileId);
			return null;
		}
		try {
			store.set(profileId, secret);
			return null;
		} catch (NuclrCredentialException e) {
			log.warn("Could not store the password for connection {}: {}", profileId, e.getMessage());
			return e.getMessage();
		}
	}

	public void deletePassword(String profileId) {
		if (!isUsableKey(profileId)) {
			return;
		}
		try {
			store.delete(profileId);
		} catch (NuclrCredentialException e) {
			log.debug("Could not delete the stored password for connection {}: {}", profileId, e.getMessage());
		}
	}

	/** @return whether a password is currently stored for {@code profileId}. */
	public boolean hasPassword(String profileId) {
		return loadPassword(profileId) != null;
	}

	/**
	 * The host requires a nonblank key containing neither NUL nor {@code '|'} (its
	 * Windows backend joins service and account with a pipe) and throws
	 * {@link IllegalArgumentException} otherwise. Profile ids are generated UUIDs and
	 * always pass, so this only guards ids that reached the store some other way.
	 */
	private static boolean isUsableKey(String profileId) {
		return profileId != null && !profileId.isBlank()
				&& profileId.indexOf('|') < 0 && profileId.indexOf('\0') < 0;
	}
}
