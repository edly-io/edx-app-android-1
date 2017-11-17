package org.edx.mobile.view;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.inject.Inject;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import org.edx.mobile.R;
import org.edx.mobile.base.BaseFragment;
import org.edx.mobile.core.IEdxEnvironment;
import org.edx.mobile.databinding.FragmentCourseTabsDashboardBinding;
import org.edx.mobile.databinding.FragmentDashboardErrorLayoutBinding;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.model.FragmentItemModel;
import org.edx.mobile.model.api.EnrolledCoursesResponse;
import org.edx.mobile.module.analytics.Analytics;
import org.edx.mobile.module.analytics.AnalyticsRegistry;
import org.edx.mobile.module.db.DataCallback;
import org.edx.mobile.util.NetworkUtil;
import org.edx.mobile.util.images.ShareUtils;
import org.edx.mobile.view.adapters.FragmentItemPagerAdapter;
import org.edx.mobile.view.custom.ProgressWheel;

import java.util.ArrayList;
import java.util.List;

import roboguice.inject.InjectExtra;

public class CourseTabsDashboardFragment extends BaseFragment {
    protected final Logger logger = new Logger(getClass().getName());

    @Nullable
    private FragmentCourseTabsDashboardBinding binding;
    @Nullable
    private FragmentDashboardErrorLayoutBinding errorLayoutBinding;

    @Inject
    private IEdxEnvironment environment;

    @InjectExtra(Router.EXTRA_COURSE_DATA)
    private EnrolledCoursesResponse courseData;

    @Inject
    private AnalyticsRegistry analyticsRegistry;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateDownloadProgressRunnable;

    @NonNull
    public static CourseTabsDashboardFragment newInstance() {
        return new CourseTabsDashboardFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActivity().setTitle(courseData.getCourse().getName());
        setHasOptionsMenu(courseData.getCourse().getCoursewareAccess().hasAccess());
        environment.getAnalyticsRegistry().trackScreenView(
                Analytics.Screens.COURSE_DASHBOARD, courseData.getCourse().getId(), null);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.course_dashboard_menu, menu);
        if (environment.getConfig().isCourseSharingEnabled()) {
            menu.findItem(R.id.menu_item_share).setVisible(true);
        } else {
            menu.findItem(R.id.menu_item_share).setVisible(false);
        }
        handleDownloadProgressMenuItem(menu);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (courseData.getCourse().getCoursewareAccess().hasAccess()) {
            binding = DataBindingUtil.inflate(inflater, R.layout.fragment_course_tabs_dashboard, container, false);
            initializeTabs();
            return binding.getRoot();
        } else {
            errorLayoutBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_dashboard_error_layout, container, false);
            errorLayoutBinding.errorMsg.setText(R.string.course_not_started);
            return errorLayoutBinding.getRoot();
        }
    }

    public void initializeTabs() {
        // Get fragment items list
        final List<FragmentItemModel> fragmentItems = getFragmentItems();
        // Init tabs
        final TabLayout tabLayout = binding.tabLayout;
        TabLayout.Tab tab;
        for (FragmentItemModel fragmentItem : fragmentItems) {
            tab = tabLayout.newTab();
            IconDrawable iconDrawable = new IconDrawable(getContext(), fragmentItem.getIcon());
            iconDrawable.colorRes(getContext(), R.color.edx_brand_primary_base);
            tab.setIcon(iconDrawable);
            tab.setContentDescription(fragmentItem.getTitle());
            tabLayout.addTab(tab);
        }
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                binding.viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
        // Init view pager
        final FragmentItemPagerAdapter adapter = new FragmentItemPagerAdapter(this.getActivity().getSupportFragmentManager(), fragmentItems);
        binding.viewPager.setAdapter(adapter);
        binding.viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout) {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                final FragmentItemModel item = fragmentItems.get(position);
                getActivity().setTitle(item.getTitle());
                if (item.getListener() != null) {
                    item.getListener().onFragmentSelected();
                }
            }
        });
        // It will load all of the fragments on creation and will stay in memory till ViewPager's
        // life time, it will greatly improve our user experience as all fragments will be available
        // to view all the time. We can decrease the limit if it creates memory problems on low-end devices.
        binding.viewPager.setOffscreenPageLimit(fragmentItems.size()-1);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_share:
                ShareUtils.showCourseShareMenu(getActivity(), getActivity().findViewById(R.id.menu_item_share),
                        courseData, analyticsRegistry, environment);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (updateDownloadProgressRunnable != null) {
            updateDownloadProgressRunnable.run();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (updateDownloadProgressRunnable != null) {
            handler.removeCallbacks(updateDownloadProgressRunnable);
        }
    }

    public void handleDownloadProgressMenuItem(Menu menu) {
        final MenuItem downloadsMenuItem = menu.findItem(R.id.menu_item_download_progress);
        final View progressView = downloadsMenuItem.getActionView();
        final ProgressWheel progressWheel = (ProgressWheel)
                progressView.findViewById(R.id.progress_wheel);
        if (downloadsMenuItem != null) {
            downloadsMenuItem.setVisible(downloadsMenuItem.isVisible());
            progressWheel.setProgress(progressWheel.getProgress());
        }
        progressView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                environment.getRouter().showDownloads(getActivity());
            }
        });
        if (updateDownloadProgressRunnable == null) {
            updateDownloadProgressRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!NetworkUtil.isConnected(getContext()) ||
                            !environment.getDatabase().isAnyVideoDownloading(null)) {
                        downloadsMenuItem.setVisible(false);
                    } else {
                        downloadsMenuItem.setVisible(true);
                        environment.getStorage().getAverageDownloadProgress(
                                new DataCallback<Integer>() {
                                    @Override
                                    public void onResult(Integer result) {
                                        int progressPercent = result;
                                        if (progressPercent >= 0 && progressPercent <= 100) {
                                            progressWheel.setProgressPercent(progressPercent);
                                        }
                                    }

                                    @Override
                                    public void onFail(Exception ex) {
                                        logger.error(ex);
                                    }
                                });
                    }
                    handler.postDelayed(this, DateUtils.SECOND_IN_MILLIS);
                }
            };
            updateDownloadProgressRunnable.run();
        }
    }

    public List<FragmentItemModel> getFragmentItems() {
        ArrayList<FragmentItemModel> items = new ArrayList<>();
        // Add course outline tab
        items.add(new FragmentItemModel(TestFragment.class, courseData.getCourse().getName(),
                FontAwesomeIcons.fa_list_alt,
                new FragmentItemModel.FragmentStateListener() {
                    @Override
                    public void onFragmentSelected() {
                        environment.getAnalyticsRegistry().trackScreenView(Analytics.Screens.COURSE_OUTLINE,
                                courseData.getCourse().getId(), null);
                    }
                }));
        // Add videos tab
        if (environment.getConfig().isCourseVideosEnabled()) {
            items.add(new FragmentItemModel(TestFragment.class,
                    getResources().getString(R.string.videos_title), FontAwesomeIcons.fa_film,
                    new FragmentItemModel.FragmentStateListener() {
                        @Override
                        public void onFragmentSelected() {
                            environment.getAnalyticsRegistry().trackScreenView(
                                    Analytics.Screens.VIDEOS_COURSE_VIDEOS, courseData.getCourse().getId(), null);
                        }
                    }));
        }
        // Add discussion tab
        if (environment.getConfig().isDiscussionsEnabled() &&
                !TextUtils.isEmpty(courseData.getCourse().getDiscussionUrl())) {
            items.add(new FragmentItemModel(CourseDiscussionTopicsFragment.class,
                    getResources().getString(R.string.discussion_title), FontAwesomeIcons.fa_comments_o,
                    new FragmentItemModel.FragmentStateListener() {
                        @Override
                        public void onFragmentSelected() {
                            environment.getAnalyticsRegistry().trackScreenView(Analytics.Screens.FORUM_VIEW_TOPICS,
                                    courseData.getCourse().getId(), null, null);
                        }
                    }));
        }
        // Add important dates tab
        if (environment.getConfig().isCourseDatesEnabled()) {
            items.add(new FragmentItemModel(CourseDatesFragment.class,
                    getResources().getString(R.string.course_dates_title), FontAwesomeIcons.fa_calendar,
                    CourseDatesFragment.makeArguments(getContext(), environment, courseData),
                    new FragmentItemModel.FragmentStateListener() {
                        @Override
                        public void onFragmentSelected() {
                            analyticsRegistry.trackScreenView(Analytics.Screens.COURSE_DATES,
                                    courseData.getCourse().getId(), null);
                        }
                    }));
        }
        // Add additional resources tab
        items.add(new FragmentItemModel(AdditionalResourcesFragment.class,
                getResources().getString(R.string.additional_resources_title),
                FontAwesomeIcons.fa_ellipsis_h, null));
        return items;
    }

    //TODO: Remove it once all tab fragments are available to attach
    public static class TestFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            TextView tv = new TextView(getContext());
            tv.setText("Content coming soon!");
            tv.setGravity(Gravity.CENTER);
            return tv;
        }
    }
}
