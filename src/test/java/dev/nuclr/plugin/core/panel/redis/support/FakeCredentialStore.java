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
package dev.nuclr.plugin.core.panel.redis.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialException.Reason;
import dev.nuclr.platform.NuclrCredentialStore;

/**
 * In-memory {@link NuclrCredentialStore} reproducing the two host behaviours the plugin
 * depends on: empty secrets are rejected, and every operation can be made to fail as an
 * unavailable OS keyring.
 */
public final class FakeCredentialStore implements NuclrCredentialStore {

	public final Map<String, String> secrets = new HashMap<>();

	private NuclrCredentialException failure;

	/** Make every subsequent operation fail the way a locked or missing keyring does. */
	public void failWith(Reason reason, String message) {
		this.failure = new NuclrCredentialException(reason, message);
	}

	@Override
	public Optional<String> get(String key) throws NuclrCredentialException {
		validate(key);
		return Optional.ofNullable(secrets.get(key));
	}

	@Override
	public void set(String key, String secret) throws NuclrCredentialException {
		validate(key);
		if (secret == null || secret.isEmpty()) {
			throw new IllegalArgumentException("A nonempty secret is required.");
		}
		secrets.put(key, secret);
	}

	@Override
	public void delete(String key) throws NuclrCredentialException {
		validate(key);
		secrets.remove(key);
	}

	private void validate(String key) throws NuclrCredentialException {
		if (key == null || key.isBlank() || key.indexOf('|') >= 0 || key.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("Credential identifiers must be nonblank and contain neither NUL nor '|'.");
		}
		if (failure != null) {
			throw failure;
		}
	}
}
