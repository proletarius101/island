package org.securesystem.insular.tip;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.cardview.widget.CardView;

import com.oasisfeng.android.util.Apps;
import org.securesystem.insular.controller.IslandAppClones;
import org.securesystem.insular.data.IslandAppInfo;
import org.securesystem.insular.data.IslandAppListProvider;
import org.securesystem.insular.engine.IslandManager;
import org.securesystem.insular.mobile.R;
import org.securesystem.insular.provisioning.CriticalAppsManager;
import org.securesystem.insular.shuttle.MethodShuttle;
import org.securesystem.insular.util.Users;
import com.oasisfeng.ui.card.CardViewModel;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Hint to solve web-view related issues / crashes, by cloning or unfreezing the current provider package of web-view.
 *
 * Created by Oasis on 2017/9/10.
 */
public class CriticalAppRequiredTip extends IgnorableTip {

	@WorkerThread @Override protected @Nullable CardViewModel buildCardIfNotIgnored(final Context context) {
		final String webview_pkg;
		if (Users.hasProfile() && (webview_pkg = CriticalAppsManager.getCurrentWebViewPackageName()) != null) {
			final IslandAppInfo app = IslandAppListProvider.getInstance(context).get(webview_pkg, Users.profile);
			if (! shouldIgnoreTip(context, webview_pkg)) {
				final CardViewModel card = buildCardIfActionRequired(context, webview_pkg, app);
				if (card != null) return card;
			}
		}
		if (mCriticalSystemPackages == null) mCriticalSystemPackages = IslandAppListProvider.getInstance(context).getCriticalSystemPackages()
				.filter(app -> app.isHidden() || ! app.enabled).collect(Collectors.toList());
		if (mCriticalSystemPackages.isEmpty()) return null;
		for (final IslandAppInfo app : mCriticalSystemPackages) {
			if (shouldIgnoreTip(context, app.packageName)) continue;
			return buildCardIfActionRequired(context, app.packageName, app);
		}
		return null;
	}

	@WorkerThread private CardViewModel buildCardIfActionRequired(final Context context, final String pkg, final @Nullable IslandAppInfo app) {
		if (app != null && app.enabled && ! app.isHidden()) return null;		// No action required for this app.
		final CardViewModel card = new CardViewModel(context, R.string.tip_critical_package_required, 0,
				getIgnoreActionLabel(), app == null ? R.string.action_clone : app.isHidden() ? R.string.action_unfreeze : R.string.action_app_settings) {

			@Override public void onButtonStartClick(final Context context, final CardView card) {
				dismiss(card);
				ignoreTip(context, pkg);
			}

			@Override public void onButtonEndClick(final Context context, final CardView card) {
				dismiss(card);
				final IslandAppInfo app_in_owner = IslandAppListProvider.getInstance(context).get(pkg);
				if (app == null && app_in_owner != null) {
					IslandAppClones.cloneApp(context, app_in_owner);
				} else if (app != null && app.isHidden()) {
					MethodShuttle.runInProfile(context, () -> IslandManager.ensureAppHiddenState(context, pkg, false));
				} else Objects.requireNonNull((LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE))
						.startAppDetailsActivity(new ComponentName(pkg, ""), Users.profile, null, null);
			}
		};
		card.text = context.getString(R.string.tip_critical_package_message, app != null ? app.getLabel() : Apps.of(context).getAppName(pkg));
		return card;
	}

	CriticalAppRequiredTip() { super("tip-critical-app-required"); }

	private List<IslandAppInfo> mCriticalSystemPackages;
}