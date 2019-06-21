package com.oasisfeng.island.provisioning;

import android.app.Activity;
import android.app.IntentService;
import android.app.Notification;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.CrossProfileApps;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.Contacts;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.oasisfeng.android.content.IntentCompat;
import com.oasisfeng.android.content.IntentFilters;
import com.oasisfeng.android.ui.Dialogs;
import com.oasisfeng.android.util.SafeAsyncTask;
import com.oasisfeng.android.util.Supplier;
import com.oasisfeng.android.util.Suppliers;
import com.oasisfeng.android.widget.Toasts;
import com.oasisfeng.island.analytics.Analytics;
import com.oasisfeng.island.api.Api;
import com.oasisfeng.island.engine.IslandManager;
import com.oasisfeng.island.engine.R;
import com.oasisfeng.island.notification.NotificationIds;
import com.oasisfeng.island.shortcut.AbstractAppLaunchShortcut;
import com.oasisfeng.island.shuttle.ServiceShuttle;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Modules;
import com.oasisfeng.island.util.OwnerUser;
import com.oasisfeng.island.util.Permissions;
import com.oasisfeng.island.util.ProfileUser;
import com.oasisfeng.island.util.Users;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.app.Notification.PRIORITY_HIGH;
import static android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT;
import static android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED;
import static android.content.Intent.ACTION_INSTALL_PACKAGE;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_SEND_MULTIPLE;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.CATEGORY_BROWSABLE;
import static android.content.Intent.CATEGORY_LAUNCHER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.N_MR1;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static androidx.core.app.NotificationCompat.BADGE_ICON_SMALL;
import static androidx.core.app.NotificationCompat.CATEGORY_STATUS;

/**
 * The one-time provisioning for newly created managed profile of Island
 *
 * Created by Oasis on 2016/4/26.
 */
public class IslandProvisioning extends IntentService {

	/**
	 * Provision state:
	 *   1 - Managed profile provision (stock) is completed
	 *   2 - Island provision is started, POST_PROVISION_REV - Island provision is completed.
	 *   [3,POST_PROVISION_REV> - Island provision is completed in previous version, but needs re-performing in this version.
	 *   POST_PROVISION_REV - Island provision is up-to-date, nothing to do.
	 */
	private static final String PREF_KEY_PROVISION_STATE = "provision.state";
	/** Provision type: 0 (default) - Managed provisioning, 1 - Manual provisioning */
	private static final String PREF_KEY_PROFILE_PROVISION_TYPE = "profile.provision.type";
	/** The revision for post-provisioning. Increase this const value if post-provisioning needs to be re-performed after upgrade. */
	private static final int POST_PROVISION_REV = 9;
	private static final String AFFILIATION_ID = "com.oasisfeng.island";
	private static final String CATEGORY_MAIN_ACTIVITY = "com.oasisfeng.island.category.MAIN_ACTIVITY";
	private static final String SCHEME_PACKAGE = "package";

	@OwnerUser @ProfileUser public static void start(final Context context, final @Nullable String action) {
		final Intent intent = new Intent(action).setComponent(new ComponentName(context, IslandProvisioning.class));
		if (SDK_INT >= O) context.startForegroundService(intent);
		else context.startService(intent);
	}

	/** This is the normal procedure after ManagedProvision finished its provisioning, running in profile. */
	@ProfileUser public static void onProfileProvisioningComplete(final Context context, final Intent intent) {
		Log.d(TAG, "onProfileProvisioningComplete");
		if (Users.isOwner()) return;		// Nothing to do for managed device provisioning.
		start(context, intent.getAction());
	}

	@OwnerUser @ProfileUser @WorkerThread @Override protected void onHandleIntent(@Nullable final Intent intent) {
		if (intent == null) return;		// Should never happen since we already setIntentRedelivery(true).
		proceed(this, intent);
	}

	@WorkerThread private static void proceed(final Context context, final Intent intent) {
		if (DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE.equals(intent.getAction())) {
			Log.d(TAG, "Re-provisioning Mainland.");
			if (! Users.isOwner()) throw new IllegalStateException("Not running in owner user");
			startDeviceOwnerPostProvisioning(context);
			Toasts.show(context, R.string.toast_reprovision_done, Toast.LENGTH_SHORT);
			return;
		}
		if (DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE.equals(intent.getAction())) {		// Borrow this activity intent for re-provision.
			Log.d(TAG, "Re-provisioning Island.");
			reprovisionManagedProfile(context);
			Toasts.show(context, R.string.toast_reprovision_done, Toast.LENGTH_SHORT);
			return;
		}
		// Grant essential permissions early, since they may be required in the following provision procedure.
		if (SDK_INT >= M) grantEssentialDebugPermissionsIfPossible(context);

		if (Users.isOwner() && DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED.equals(intent.getAction())) {	// ACTION_DEVICE_OWNER_CHANGED is added in Android 6.
			Analytics.$().event("device_provision_manual_start").send();
			startDeviceOwnerPostProvisioning(context);
			return;
		}

		final boolean is_manual_setup = Intent.ACTION_USER_INITIALIZE.equals(intent.getAction()) || intent.getAction() == null/* recovery procedure triggered by MainActivity */;
		Analytics.$().setProperty(Analytics.Property.IslandSetup, is_manual_setup ? "manual" : "managed");
		Log.d(TAG, "Provisioning profile (" + Users.toId(android.os.Process.myUserHandle()) + (is_manual_setup ? ", manual) " : ")"));

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		prefs.edit().putInt(PREF_KEY_PROVISION_STATE, 1).putInt(PREF_KEY_PROFILE_PROVISION_TYPE, is_manual_setup ? 1 : 0).apply();
		final DevicePolicies policies = new DevicePolicies(context);
		if (is_manual_setup) {		// Do the similar job of ManagedProvisioning here.
			Log.d(TAG, "Manual provisioning");
			Analytics.$().event("profile_post_provision_manual_start").send();
			ProfileOwnerManualProvisioning.start(context, policies);	// Mimic the stock managed profile provision
		} else Analytics.$().event("profile_post_provision_start").send();

		Log.d(TAG, "Start post-provisioning.");
		try {
			startProfileOwnerPostProvisioning(context, policies);
		} catch (final Exception e) {
			Analytics.$().event("profile_post_provision_error").with(Analytics.Param.ITEM_NAME, e.toString()).send();
			Analytics.$().report(e);
		}

		// Prepare critical apps
		enableCriticalAppsIfNeeded(context, policies);
		// Disable unnecessarily enabled apps
		if (! Users.isOwner()) hideUnnecessaryAppsInManagedProfile(context);	// Users.isProfile() does not work before setProfileEnabled().

		if (! is_manual_setup) {	// Enable the profile here, launcher will show all apps inside.
			policies.execute(DevicePolicyManager::setProfileName, context.getString(R.string.profile_name));
			Log.d(TAG, "Enable profile now.");
			policies.execute(DevicePolicyManager::setProfileEnabled);
		}
		Analytics.$().event("profile_post_provision_done").send();

		disableLauncherActivity(context);
		prefs.edit().putInt(PREF_KEY_PROVISION_STATE, POST_PROVISION_REV).apply();

		if (! launchMainActivityInOwnerUser(context)) {
			Analytics.$().event("error_launch_main_ui").send();
			Log.e(TAG, "Failed to launch main activity in owner user.");
			Toasts.show(context, R.string.toast_setup_complete, Toast.LENGTH_LONG);
		}
	}

	@ProfileUser private static void hideUnnecessaryAppsInManagedProfile(final Context context) {
		final List<ResolveInfo> resolves = context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_PICK, Contacts.CONTENT_URI),
				SDK_INT >= N ? PackageManager.MATCH_SYSTEM_ONLY : 0);		// Do not use resolveActivity(), which will return ResolverActivity.
		if (resolves != null) for (final ResolveInfo resolve : resolves) {
			final String pkg = resolve.activityInfo.packageName;
			if ((resolve.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || "android".equals(pkg)) continue;
			if (context.getPackageManager().resolveActivity(new Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(pkg), 0) != null)
				new DevicePolicies(context).setApplicationHiddenWithoutAppOpsSaver(pkg, true);
		}
	}

	@RequiresApi(M) private static void grantEssentialDebugPermissionsIfPossible(final Context context) {
		if (Permissions.ensure(context, Permissions.INTERACT_ACROSS_USERS)) Permissions.ensure(context, WRITE_SECURE_SETTINGS);
	}

	@WorkerThread public static void performIncrementalProfileOwnerProvisioningIfNeeded(final Context context) {
		try {
			startProfileOwnerPostProvisioning(context, new DevicePolicies(context));
		} catch (final RuntimeException e) {
			Analytics.$().logAndReport(TAG, "Error provisioning profile", e);
		}
	}

	@Override public void onCreate() {
		super.onCreate();
		NotificationIds.Provisioning.startForeground(this, mForegroundNotification.get());
	}

	@Override public void onDestroy() {
		stopForeground(true);
		super.onDestroy();
	}

	@ProfileUser private static boolean launchMainActivityInOwnerUser(final Context context) {
		final ComponentName activity = Modules.getMainLaunchActivity(context);
		if (SDK_INT == P) try {		// Not working on Android Q beta 4. TODO: verify it on Android Q final version
			context.getSystemService(CrossProfileApps.class).startMainActivity(activity, Users.owner);
			return true;
		} catch (final RuntimeException e) {
			Log.e(TAG, "Error launching main activity in owner user directly.", e);	// Fall-through
		}

		final LauncherApps apps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
		if (apps == null) return false;
		if (apps.isActivityEnabled(activity, Users.owner)) {
			apps.startMainActivity(activity, Users.owner, null, null);
			return true;
		}
		// Since Android O, activities in owner user is invisible to managed profile, use special forward rule to launch it in owner user.
		new DevicePolicies(context).execute(DevicePolicyManager::addCrossProfileIntentFilter,
				IntentFilters.forAction(ACTION_MAIN).withCategory(CATEGORY_MAIN_ACTIVITY), FLAG_PARENT_CAN_ACCESS_MANAGED);
		try {
			context.startActivity(new Intent(ACTION_MAIN).addCategory(CATEGORY_MAIN_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
			return true;
		} catch (final ActivityNotFoundException e) {
			return false;
		}
	}

	@ProfileUser private static void disableLauncherActivity(final Context context) {		// To mark the finish of post-provisioning
		try {
			context.getPackageManager().setComponentEnabledSetting(Modules.getMainLaunchActivity(context), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
		} catch (final SecurityException e) {
			// FIXME: No permission to alter another module.
		}
	}

	@ProfileUser private static void enableCriticalAppsIfNeeded(final Context context, final DevicePolicies policies) {
		final Set<String> pkgs = CriticalAppsManager.detectCriticalPackages(context.getPackageManager());
		for (final String pkg : pkgs) try {
			policies.enableSystemApp(pkg);        // FIXME: Don't re-enable explicitly cloned system apps. (see ClonedHiddenSystemApps)
			policies.invoke(DevicePolicyManager::setApplicationHidden, pkg, false);
		} catch (final IllegalArgumentException ignored) {}		// Ignore non-existent packages.
	}

	@ProfileUser @WorkerThread public static void reprovisionManagedProfile(final Context context) {
		// Always perform all the required provisioning steps covered by stock ManagedProvisioning, in case something is missing there.
		// This is also required for manual provision via ADB shell.
		final DevicePolicies policies = new DevicePolicies(context);
		policies.execute(DevicePolicyManager::clearCrossProfileIntentFilters);

		final int provision_type = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_KEY_PROFILE_PROVISION_TYPE, 0);
		if (provision_type == 1) ProfileOwnerManualProvisioning.start(context, policies);	// Simulate the stock managed profile provision

		startProfileOwnerPostProvisioning(context, policies);
		disableLauncherActivity(context);
	}

	public static void startDeviceAndProfileOwnerSharedPostProvisioning(final Context context, final DevicePolicies policies) {
		if (SDK_INT >= N) {
			policies.execute(DevicePolicyManager::setShortSupportMessage, context.getText(R.string.device_admin_support_message_short));
			policies.execute(DevicePolicyManager::setLongSupportMessage, context.getText(R.string.device_admin_support_message_long));
		}
		// As reported by user, some account types are strangely unable to remove. Just make sure all account types are allowed.
		final String[] restricted_account_types = policies.getManager().getAccountTypesWithManagementDisabled();
		if (restricted_account_types != null && restricted_account_types.length > 0) for (final String account_type : restricted_account_types)
			policies.execute(DevicePolicyManager::setAccountManagementDisabled, account_type, false);
	}

	/** All the preparations after the provisioning procedure of system ManagedProvisioning */
	@OwnerUser public static void startDeviceOwnerPostProvisioning(final Context context) {
		final DevicePolicies policies = new DevicePolicies(context);
		if (! policies.isActiveDeviceOwner()) return;
		startDeviceAndProfileOwnerSharedPostProvisioning(context, policies);

		policies.clearUserRestrictionsIfNeeded(context, UserManager.DISALLOW_SHARE_LOCATION);		// May be restricted on some devices (e.g. LG V20)
		if (SDK_INT >= O) {
			final Set<String> ids = Collections.singleton(AFFILIATION_ID);
			final Set<String> current_ids = policies.invoke(DevicePolicyManager::getAffiliationIds);
			if (! ids.equals(current_ids)) try {
				policies.execute(DevicePolicyManager::setAffiliationIds, ids);
			} catch (final SecurityException ignored) {}	// SecurityException will be thrown if profile is not managed by Island.
			policies.clearUserRestrictionsIfNeeded(context, UserManager.DISALLOW_ADD_MANAGED_PROFILE);	// Ref: UserRestrictionsUtils.DEFAULT_ENABLED_FOR_DEVICE_OWNERS
		}
		try {
			if (SDK_INT >= N_MR1 && ! policies.isBackupServiceEnabled())
				policies.setBackupServiceEnabled(true);
		} catch (final SecurityException e) {
			if (SDK_INT != N_MR1 || ! e.getMessage().equals("There should only be one user, managed by Device Owner")) Analytics.$().report(e);
		} catch (final IllegalStateException e) {
			Analytics.$().report(e);
		}
	}

	/** All the preparations after the provisioning procedure of system ManagedProvisioning, also shared by manual and incremental provisioning. */
	@ProfileUser @WorkerThread private static void startProfileOwnerPostProvisioning(final Context context, final DevicePolicies policies) {
		if (SDK_INT >= O) {
			policies.execute(DevicePolicyManager::setAffiliationIds, Collections.singleton(AFFILIATION_ID));
			policies.clearUserRestrictionsIfNeeded(context, UserManager.DISALLOW_BLUETOOTH_SHARING);
		}
		if (SDK_INT >= M) {
			grantEssentialDebugPermissionsIfPossible(context);
			policies.addUserRestrictionIfNeeded(context, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING);
		}

		startDeviceAndProfileOwnerSharedPostProvisioning(context, policies);

		IslandManager.ensureLegacyInstallNonMarketAppAllowed(context, policies);

		enableAdditionalForwarding(context, policies);

		// Prepare AppLaunchShortcut
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(AbstractAppLaunchShortcut.ACTION_LAUNCH_CLONE).withDataSchemes("target", "package")
				.withCategories(Intent.CATEGORY_DEFAULT, CATEGORY_LAUNCHER), FLAG_MANAGED_CAN_ACCESS_PARENT);

		// Prepare ServiceShuttle
		policies.addCrossProfileIntentFilter(new IntentFilter(ServiceShuttle.ACTION_BIND_SERVICE), FLAG_MANAGED_CAN_ACCESS_PARENT);

		// Prepare API
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_FREEZE).withDataSchemes("package", "packages"), FLAG_MANAGED_CAN_ACCESS_PARENT);
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_UNFREEZE).withDataSchemes("package", "packages"), FLAG_MANAGED_CAN_ACCESS_PARENT);
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Api.latest.ACTION_LAUNCH).withDataSchemes("package", "intent"), FLAG_MANAGED_CAN_ACCESS_PARENT);

		// For Greenify (non-root automated hibernation for apps in Island)
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withDataScheme("package"), FLAG_MANAGED_CAN_ACCESS_PARENT);

		// Some Samsung devices default to restrict all 3rd-party cross-profile services (IMEs, accessibility and etc).
		policies.execute(DevicePolicyManager::setPermittedInputMethods, null);
		policies.execute(DevicePolicyManager::setPermittedAccessibilityServices, null);
		if (SDK_INT >= O) policies.invoke(DevicePolicyManager::setPermittedCrossProfileNotificationListeners, null);
	}

	private static void enableAdditionalForwarding(final Context context, final DevicePolicies policies) {
		final int FLAGS_BIDIRECTIONAL = FLAG_MANAGED_CAN_ACCESS_PARENT | FLAG_PARENT_CAN_ACCESS_MANAGED;
		// For sharing across Island (bidirectional)
		policies.addCrossProfileIntentFilter(new IntentFilter(ACTION_SEND), FLAGS_BIDIRECTIONAL);		// Keep for historical compatibility reason
		try {
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_SEND).withDataType("*/*"), FLAGS_BIDIRECTIONAL);
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_SEND_MULTIPLE).withDataType("*/*"), FLAGS_BIDIRECTIONAL);
		} catch (final IntentFilter.MalformedMimeTypeException ignored) {}
		// For web browser
		policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_VIEW).withCategory(CATEGORY_BROWSABLE).withDataSchemes("http", "https", "ftp"),
				FLAG_PARENT_CAN_ACCESS_MANAGED);
		try {	// For Package Installer
			policies.addCrossProfileIntentFilter(IntentFilters.forActions(ACTION_VIEW, ACTION_INSTALL_PACKAGE)
					.withDataScheme(ContentResolver.SCHEME_CONTENT).withDataType("application/vnd.android.package-archive"), FLAGS_BIDIRECTIONAL);
			policies.addCrossProfileIntentFilter(IntentFilters.forAction(ACTION_INSTALL_PACKAGE).withCategory(context.getPackageName())	// Additional category to bypass system package installer
					.withDataSchemes(ContentResolver.SCHEME_CONTENT, SCHEME_PACKAGE), FLAG_MANAGED_CAN_ACCESS_PARENT);	// One-way only
		} catch (final IntentFilter.MalformedMimeTypeException ignored) {}
		policies.addCrossProfileIntentFilter(IntentFilters.forActions(IntentCompat.ACTION_SHOW_APP_INFO), FLAG_PARENT_CAN_ACCESS_MANAGED);
	}

	public IslandProvisioning() {
		super(TAG);
		setIntentRedelivery(true);
	}

	private final Supplier<Notification.Builder> mForegroundNotification = Suppliers.memoize(() -> {
		final Notification.Builder builder = new Notification.Builder(this).setPriority(PRIORITY_HIGH).setCategory(CATEGORY_STATUS)
				.setSmallIcon(android.R.drawable.stat_notify_sync).setColor(getResources().getColor(R.color.accent)).setUsesChronometer(true)
				.setContentTitle(getText(Users.isOwner() ? R.string.notification_provisioning_mainland_title : R.string.notification_provisioning_island_title))
				.setContentText(getText(R.string.notification_provisioning_text));
		return SDK_INT < O ? builder : builder.setBadgeIconType(BADGE_ICON_SMALL).setColorized(true);
	});

	/** Receives {@link DevicePolicyManager#ACTION_PROVISIONING_SUCCESSFUL} in managed profile */
	public static class CompletionActivity extends Activity {

		@Override protected void onCreate(@Nullable final Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			if (Users.isProfileManagedByIsland()) {		// This is true only after DevicePolicyManager.setProfileEnabled().
				Log.w(TAG, getClass().getSimpleName() + " should not be started after provisioning.");
				return;
			}
			Dialogs.buildProgress(this, R.string.notification_provisioning_island_title).indeterminate().nonCancelable().show();
			SafeAsyncTask.execute(this, a -> proceed(a, new Intent(DeviceAdminReceiver.ACTION_PROFILE_PROVISIONING_COMPLETE)), Activity::finish);
		}
	}

	private static final String TAG = "Island.Provision";
}
