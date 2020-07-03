package org.securesystem.insular.engine;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.oasisfeng.android.util.SafeSharedPreferences;
import org.securesystem.insular.data.IslandAppInfo;
import org.securesystem.insular.data.IslandAppListProvider;
import org.securesystem.insular.shuttle.MethodShuttle;
import org.securesystem.insular.util.OwnerUser;
import org.securesystem.insular.util.Permissions;
import org.securesystem.insular.util.Users;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static android.content.Context.USER_SERVICE;
import static java.util.Objects.requireNonNull;

/** Track explicitly "cloned" (unfrozen) system apps (previous frozen in post-provisioning). Other frozen system apps should be treated as "disabled" in UI. */
@OwnerUser public class ClonedHiddenSystemApps {

	private static final String SHARED_PREFS_PREFIX_ENABLED_SYSTEM_APPS = "cloned_system_apps_u"/* + user serial number */;
	private static final String PREF_KEY_VERSION = "_version";
	private static final int CURRENT_VERSION = 1;

	/** @return {@link PackageManager#COMPONENT_ENABLED_STATE_DEFAULT} or {@link PackageManager#COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED} */
	public boolean isCloned(final String pkg) {
		return mStore.getInt(pkg, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
	}

	public void setCloned(final String pkg) {
		mStore.edit().putInt(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED).apply();
	}

	public ClonedHiddenSystemApps(final Context context, final UserHandle user, final Consumer<String> update_observer) {
		mUser = user;
		mStore = getStore(context, user);
		mStore.registerOnSharedPreferenceChangeListener(mChangeListener);
		mUpdateObserver = update_observer;
	}

	public static void reset(final Context context, final UserHandle user) {
		getStore(context, user).edit().clear().apply();
	}

	/** Must be called only once in one process, preferably by lazy-initialization in content provider. */
	@OwnerUser public void initializeIfNeeded(final Context context) {
		final int version = mStore.getInt(PREF_KEY_VERSION, 0);
		if (version < CURRENT_VERSION) initialize(context);
	}

	@OwnerUser private void initialize(final Context context) {
		final long begin_time = requireNonNull((UserManager) context.getSystemService(USER_SERVICE)).getUserCreationTime(mUser);

		MethodShuttle.runInProfile(context, () -> queryUsedPackagesDuring(context, begin_time, System.currentTimeMillis())).thenAccept(used_pkgs -> {
			final SharedPreferences.Editor editor = mStore.edit().clear();
			if (used_pkgs.size() > 0) {
				final IslandAppListProvider apps = IslandAppListProvider.getInstance(context);
				for (final String pkg : used_pkgs) {
					final IslandAppInfo app = apps.get(pkg, mUser);
					if (app == null || ! app.isSystem()) continue;
					editor.putInt(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
				}
			} else Log.w(TAG, "No used system apps");	// Failed to query, may not have required permission, treat all hidden packages as disabled.
			editor.putInt(PREF_KEY_VERSION, 1).apply();
		});
	}

	/** Query the used packages during the given time span. (works on Android 6+ or Android 5.x with PACKAGE_USAGE_STATS permission granted manually) */
	private static Collection<String> queryUsedPackagesDuring(final Context context, final long begin_time, final long end_time) {
		if (! Permissions.has(context, Manifest.permission.PACKAGE_USAGE_STATS)) return Collections.emptySet();
		@SuppressLint("InlinedApi") final UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE); /* hidden but accessible on API 21 */
		if (usm == null) return Collections.emptySet();
		final Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(begin_time, end_time);
		if (stats == null) return Collections.emptySet();
		return stats.values().stream().filter(usage -> usage.getLastTimeUsed() != 0).map(UsageStats::getPackageName)
				.collect(Collectors.toList());
	}

	private static SharedPreferences getStore(final Context context, final UserHandle user) {
		final UserManager um = (UserManager) context.getSystemService(USER_SERVICE);
		final long usn = um != null ? um.getSerialNumberForUser(user) : Users.toId(user);
		return SafeSharedPreferences.wrap(context.getSharedPreferences(SHARED_PREFS_PREFIX_ENABLED_SYSTEM_APPS + usn, Context.MODE_PRIVATE));
	}

	@SuppressWarnings("FieldCanBeLocal") private final SharedPreferences.OnSharedPreferenceChangeListener mChangeListener = (sp, key) -> {
		Log.d(TAG, "Updated: " + key);
		if (key.startsWith("_")) return;
		ClonedHiddenSystemApps.this.mUpdateObserver.accept(key);
	};

	private final SharedPreferences mStore;
	private final UserHandle mUser;
	private final Consumer<String> mUpdateObserver;

	private static final String TAG = "Island.HiddenSysApp";
}