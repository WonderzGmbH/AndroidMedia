/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.offline;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.scheduler.Scheduler;
import androidx.media3.test.utils.InMemoryDatabaseRule;
import androidx.media3.test.utils.robolectric.FakeDownloader;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowService;

/** Unit tests for {@link DownloadService}. */
@RunWith(AndroidJUnit4.class)
@Config(shadows = DownloadServiceTest.ShadowRefusingService.class)
public class DownloadServiceTest {

  @Rule public final InMemoryDatabaseRule inMemoryDatabaseRule = InMemoryDatabaseRule.create();

  private static DownloadManager downloadManager;

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    ShadowRefusingService.refuse = true;
    ShadowRefusingService.refusals = 0;
    downloadManager =
        new DownloadManager(
            context,
            new DefaultDownloadIndex(inMemoryDatabaseRule.createDatabaseProvider()),
            FakeDownloader::new);
    downloadManager.setRequirements(new Requirements(0));
  }

  @After
  public void tearDown() {
    downloadManager.release();
    DownloadService.clearDownloadManagerHelpers();
  }

  @Test
  public void downloadStart_foregroundRefused_keepsRunningWithoutRetryingEverySecond()
      throws Exception {
    ServiceController<TestDownloadService> service =
        Robolectric.buildService(TestDownloadService.class).create();

    downloadManager.addDownload(downloadRequest("id1"));
    runMainLooperUntil(() -> ShadowRefusingService.refusals > 0);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));

    assertThat(ShadowRefusingService.refusals).isEqualTo(1);
    assertThat(shadowOf(service.get()).getLastForegroundNotification()).isNull();
  }

  @Test
  public void downloadStart_foregroundRefused_nextDownloadChangeStartsForegroundOnceAllowed()
      throws Exception {
    ServiceController<TestDownloadService> service =
        Robolectric.buildService(TestDownloadService.class).create();
    downloadManager.addDownload(downloadRequest("id1"));
    runMainLooperUntil(() -> ShadowRefusingService.refusals > 0);

    ShadowRefusingService.refuse = false;
    downloadManager.addDownload(downloadRequest("id2"));
    runMainLooperUntil(() -> shadowOf(service.get()).getLastForegroundNotification() != null);

    assertThat(ShadowRefusingService.refusals).isEqualTo(1);
  }

  @Test
  public void startInForeground_foregroundRefused_doesNotThrow() {
    Intent intent =
        new Intent(context, TestDownloadService.class)
            .setAction(DownloadService.ACTION_INIT)
            .putExtra(DownloadService.KEY_FOREGROUND, true);

    ServiceController<TestDownloadService> service =
        Robolectric.buildService(TestDownloadService.class, intent).create().startCommand(0, 1);

    assertThat(ShadowRefusingService.refusals).isEqualTo(1);
    assertThat(shadowOf(service.get()).getLastForegroundNotification()).isNull();
  }

  private static DownloadRequest downloadRequest(String id) {
    return new DownloadRequest.Builder(id, Uri.parse("https://example.test/" + id)).build();
  }

  /** A {@link DownloadService} that uses the {@link DownloadManager} of the current test. */
  public static final class TestDownloadService extends DownloadService {

    public TestDownloadService() {
      super(/* foregroundNotificationId= */ 1);
    }

    @Override
    protected DownloadManager getDownloadManager() {
      return downloadManager;
    }

    @Override
    @Nullable
    protected Scheduler getScheduler() {
      return null;
    }

    @Override
    protected Notification getForegroundNotification(
        List<Download> downloads, @Requirements.RequirementFlags int notMetRequirements) {
      return new Notification();
    }
  }

  /** A {@link Service} shadow that refuses {@code startForeground()} while {@link #refuse}. */
  @Implements(Service.class)
  public static final class ShadowRefusingService extends ShadowService {

    private static boolean refuse;
    private static int refusals;

    @Implementation
    @Override
    protected void startForeground(int id, Notification notification, int foregroundServiceType)
        throws Exception {
      if (refuse) {
        refusals++;
        throw new ForegroundServiceStartNotAllowedException(
            "Service.startForeground() not allowed due to mAllowStartForeground false");
      }
      super.startForeground(id, notification, foregroundServiceType);
    }
  }
}
