package com.oasisfeng.island;

import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.databinding.Observable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.oasisfeng.android.ui.AppLabelCache;
import com.oasisfeng.island.data.AppListViewModel;
import com.oasisfeng.island.data.AppViewModel;
import com.oasisfeng.island.data.AppViewModel.State;
import com.oasisfeng.island.databinding.AppListBinding;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static android.content.pm.ApplicationInfo.FLAG_INSTALLED;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES;

/** The main UI - App list */
public class AppListFragment extends Fragment {

	private static final String KStateKeyRecyclerView = "apps.recycler.layout";
	/** System packages shown to user always even if no launcher activities */
	private static final Collection<String> sVisibleSysPackages = Collections.singletonList("com.google.android.gms");

	@Override public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mIslandManager = new IslandManager(getActivity());
		mViewModel = new AppListViewModel(mIslandManager);
		mViewModel.addOnPropertyChangedCallback(onPropertyChangedCallback);

		mIslandManager.startProfileOwnerProvisioningIfNeeded();

		final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
		filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
		filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
		filter.addDataScheme("package");
		getActivity().registerReceiver(mPackageEventsObserver, filter);

		final IntentFilter pkgs_filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
		pkgs_filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
		getActivity().registerReceiver(mPackagesEventsObserver, pkgs_filter);

		loadAppList(false);
	}

	@Override public void onDestroy() {
		mViewModel.removeOnPropertyChangedCallback(onPropertyChangedCallback);
		getActivity().unregisterReceiver(mPackagesEventsObserver);
		getActivity().unregisterReceiver(mPackageEventsObserver);
		super.onDestroy();
	}

	private final BroadcastReceiver mPackageEventsObserver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		final Uri data = intent.getData();
		if (data == null) return;
		final String pkg = data.getSchemeSpecificPart();
		if (pkg == null || context.getPackageName().equals(pkg)) return;
		mViewModel.updateApp(pkg);
		invalidateOptionsMenu();
	}};

	private final BroadcastReceiver mPackagesEventsObserver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent intent) {
		final String[] pkgs = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
		if (pkgs == null) return;
		for (final String pkg : pkgs)
			mViewModel.updateApp(pkg);
		invalidateOptionsMenu();
	}};

	private final Observable.OnPropertyChangedCallback onPropertyChangedCallback = new Observable.OnPropertyChangedCallback() {
		@Override public void onPropertyChanged(final Observable observable, final int var) {
			if (var == com.oasisfeng.island.BR.selection) invalidateOptionsMenu();
		}
	};

	private void invalidateOptionsMenu() {
		final Activity activity = getActivity();
		if (activity != null) activity.invalidateOptionsMenu();
	}

	@Nullable @Override public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
		setHasOptionsMenu(true);
		mBinding = AppListBinding.inflate(inflater, container, false);
		mBinding.setApps(mViewModel);
		mBinding.appList.setLayoutManager(new LinearLayoutManager(getActivity()));
		return mBinding.getRoot();
	}

	@Override public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
		inflater.inflate(R.menu.main_actions, menu);
	}

	@Override public void onPrepareOptionsMenu(final Menu menu) {
		final AppViewModel app = mViewModel.getSelection();
		menu.findItem(R.id.menu_show_all).setChecked(mShowAllApps);
		menu.findItem(R.id.menu_create_shortcut).setVisible(app != null && app.getState() == State.Alive);
		if (BuildConfig.DEBUG) menu.findItem(R.id.menu_test).setVisible(true);
	}

	@Override public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_show_all:
			item.setChecked(mViewModel.include_sys_apps = mShowAllApps = ! item.isChecked());	// Toggle the checked state
			mViewModel.clearSelection();
			loadAppList(mShowAllApps);
			return true;
		case R.id.menu_destroy:
			mIslandManager.destroy();
			return true;
		case R.id.menu_create_shortcut:
			try {
				final AppViewModel app = mViewModel.getSelection();
				if (app == null) return true;
				if (AppLaunchShortcut.createOnLauncher(getActivity(), app.pkg)) {
					Toast.makeText(getActivity(), "Shortcut created on your launcher", Toast.LENGTH_SHORT).show();
				} else Toast.makeText(getActivity(), "Failed", Toast.LENGTH_SHORT).show();
			} catch (final PackageManager.NameNotFoundException e) {
				Toast.makeText(getActivity(), "App not alive", Toast.LENGTH_SHORT).show();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override public void onStop() {
		mBinding.getApps().clearSelection();
		super.onStop();
	}

	private void loadAppList(final boolean all) {
		Log.d(TAG, all ? "Start loading app list (all)..." : "Start loading app list...");
		// Build app list if not yet (or invalidated)
		new AsyncTask<Void, Void, Map<String, ApplicationInfo>>() {
			@Override protected Map<String, ApplicationInfo> doInBackground(final Void... params) {
				final Activity activity = getActivity();
				if (activity == null) return Collections.emptyMap();
				return populateApps(all);
			}

			@Override protected void onPostExecute(final Map<String, ApplicationInfo> items) {
				fillAppViewModels(items);
			}
		}.execute();
	}

	@Override public void onSaveInstanceState(final Bundle out_state) {
		super.onSaveInstanceState(out_state);
		out_state.putParcelable(KStateKeyRecyclerView, mBinding.appList.getLayoutManager().onSaveInstanceState());
	}

	@Override public void onViewStateRestored(final Bundle saved_state) {
		super.onViewStateRestored(saved_state);
		if (saved_state != null)
			mBinding.appList.getLayoutManager().onRestoreInstanceState(saved_state.getParcelable(KStateKeyRecyclerView));
	}

	private Map<String, ApplicationInfo> populateApps(final boolean all) {
		final Activity activity = getActivity();

		final UserHandle this_user = Process.myUserHandle();
		final LauncherApps launcher = (LauncherApps) activity.getSystemService(Context.LAUNCHER_APPS_SERVICE);
		final String this_pkg = activity.getPackageName();
		//noinspection WrongConstant
		final List<ApplicationInfo> installed_apps = activity.getPackageManager().getInstalledApplications(GET_UNINSTALLED_PACKAGES);
		final ImmutableList<ApplicationInfo> apps = FluentIterable.from(installed_apps)
				// TODO: Also include system apps with running services (even if no launcher activities)
				.filter(app -> (app.flags & FLAG_SYSTEM) == 0 ? ! this_pkg.equals(app.packageName)		// Exclude Island
						: (all || (sVisibleSysPackages.contains(app.packageName) || hasActivities(launcher, app.packageName, this_user))))
				// Cloned apps first to optimize the label and icon loading experience.
				.toSortedList(Ordering.explicit(true, false).onResultOf(info -> (info.flags & FLAG_INSTALLED) != 0));

		final Map<String, ApplicationInfo> app_vm_by_pkg = new LinkedHashMap<>();	// LinkedHashMap to preserve the order
		for (final ApplicationInfo app : apps)
			app_vm_by_pkg.put(app.packageName, app);
		return app_vm_by_pkg;
	}

	private boolean hasActivities(final LauncherApps launcher, final String pkg, final UserHandle user) {
		return ! launcher.getActivityList(pkg, user).isEmpty()
				|| (! user.equals(OWNER) && ! launcher.getActivityList(pkg, OWNER).isEmpty());
	}

	private void fillAppViewModels(final Map<String/* pkg */, ApplicationInfo> app_vms) {
		final Activity activity = getActivity();
		if (activity == null) return;
		final AppLabelCache cache = AppLabelCache.load(getActivity());
		mViewModel.removeAllApps();

		cache.loadLabelTextOnly(app_vms.keySet(), new AppLabelCache.LabelLoadCallback() {
			@Override public boolean isCancelled(final String pkg) {
				return false;
			}

			@Override public void onTextLoaded(final String pkg, final CharSequence text, final int flags) {
				Log.d(TAG, "onTextLoaded for " + pkg + ": " + text);
				AppViewModel item = mViewModel.getApp(pkg);
				if (item == null) {
					item = mViewModel.addApp(pkg, text, flags);
					Log.v(TAG, "Add: " + item.pkg);
				} else Log.v(TAG, "Replace: " + item.pkg);
			}

			@Override public void onError(final String pkg, final Throwable error) {
				Log.w(TAG, "Failed to load label for " + pkg, error);
			}

			@Override public void onIconLoaded(final String pkg, final Drawable icon) {}
		});
	}

	/** Mandatory empty constructor for the fragment manager to instantiate the fragment (e.g. upon screen orientation changes). */
	public AppListFragment() {}

	private IslandManager mIslandManager;
	private AppListViewModel mViewModel;
	private AppListBinding mBinding;
	private boolean mShowAllApps;

	private static final UserHandle OWNER;
	static {
		final Parcel p = Parcel.obtain();
		try {
			p.writeInt(0);
			p.setDataPosition(0);
			OWNER = new UserHandle(p);
		} finally {
			p.recycle();
		}
	}
	private static final String TAG = "AppListFragment";
}
