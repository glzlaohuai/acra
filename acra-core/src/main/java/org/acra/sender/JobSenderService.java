package org.acra.sender;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import android.os.PersistableBundle;
import android.support.annotation.RequiresApi;
import org.acra.config.CoreConfiguration;
import org.acra.util.IOUtils;

/**
 * @author Lukas
 * @since 31.12.2018
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
public class JobSenderService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        PersistableBundle extras = params.getExtras();
        final CoreConfiguration config = IOUtils.deserialize(CoreConfiguration.class, extras.getString(LegacySenderService.EXTRA_ACRA_CONFIG));
        final boolean onlySilent = extras.getBoolean(LegacySenderService.EXTRA_ONLY_SEND_SILENT_REPORTS);
        if (config != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    new SendingConductor(JobSenderService.this, config).sendReports(onlySilent);
                    JobSenderService.this.jobFinished(params, false);
                }
            }).start();
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
