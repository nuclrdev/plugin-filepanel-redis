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

import java.util.Locale;

import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.plugin.NuclrPluginContext;

/**
 * Minimal {@link NuclrPluginContext} for tests: the settings and credential stores the
 * plugin reads in {@code preinit}, plus a fixed locale. The event bus and theme are not
 * touched by any code path under test.
 */
public final class FakeContext implements NuclrPluginContext {

	public final FakeSettings settings = new FakeSettings();
	public final FakeCredentialStore credentials = new FakeCredentialStore();

	@Override
	public NuclrCredentialStore getCredentialStore() {
		return credentials;
	}

	@Override
	public NuclrEventBus getEventBus() {
		return null;
	}

	@Override
	public NuclrThemeScheme getTheme() {
		return null;
	}

	@Override
	public NuclrSettings getSettings() {
		return settings;
	}

	@Override
	public Locale getLocale() {
		return Locale.US;
	}
}
