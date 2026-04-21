package org.adaway.model.vpn;

import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.model.error.HostError.ENABLE_VPN_FAIL;

import android.content.Context;
import android.util.LruCache;
import android.widget.Toast;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.db.entity.ListType;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.error.HostErrorException;
import org.adaway.vpn.VpnServiceControls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import timber.log.Timber;


import org.adaway.db.dao.HostListItemDao;

/**
 * This class is the model to represent VPN service configuration.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnModel extends AdBlockModel {
    private final HostEntryDao hostEntryDao;

    private final HostListItemDao hostListItemDao;
    private final LruCache<String, HostEntry> blockCache;
    private final LinkedHashSet<String> logs;
    private boolean recordingLogs;
    private int requestCount;
    private final Set<String> suffixRules = new HashSet<>();
    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public VpnModel(Context context) {
        super(context);
        AppDatabase database = AppDatabase.getInstance(context);
        this.hostEntryDao = database.hostEntryDao();
        this.hostListItemDao = database.hostsListItemDao();
        this.blockCache = new LruCache<String, HostEntry>(4 * 1024) {
            @Override
            protected HostEntry create(String host) {

                host = host.toLowerCase();

                // 🔥 WILDCARD / SUFFIX MATCH HERE
                if (isBlockedByList(host)) {
                    Timber.d("Blocked (wildcard): %s", host);

                    HostEntry entry = new HostEntry();
                    entry.setHost(host);
                    entry.setType(ListType.BLOCKED);

                    return entry;
                }

                // fallback to DB
                return VpnModel.this.hostEntryDao.getEntry(host);
            }
        };
        this.logs = new LinkedHashSet<>();
        this.recordingLogs = false;
        this.requestCount = 0;
        this.applied.postValue(VpnServiceControls.isRunning(context));
    }

    private void loadRules() {
        suffixRules.clear();

        List<HostEntry> entries = hostEntryDao.getAll(); // make sure this exists

        for (HostEntry entry : entries) {
            String host = entry.getHost(); // adjust if needed

            if (host == null) continue;

            suffixRules.add(host.toLowerCase());
        }

        Timber.d("Loaded %d suffix rules", suffixRules.size());
    }


    private boolean isBlockedByList(String host) {
        host = host.toLowerCase();

        for (String rule : suffixRules) {
            if (host.equals(rule) || host.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }


    @Override
    public AdBlockMethod getMethod() {
        return VPN;
    }

    @Override
    public void apply() throws HostErrorException {
        // Clear cache
        this.blockCache.evictAll();


        // 🔥 LOAD RULES HERE
        loadRules();


        if(this.hostListItemDao.getBlockedHostCount_()==0) {
            setState2("<font color='red'>Can't start anti-ban VPN with zero blocked hosts</font>");
            return;
        }

        // Start VPN
        boolean started = VpnServiceControls.start(this.context);
        this.applied.postValue(started);
        if (!started) {
            throw new HostErrorException(ENABLE_VPN_FAIL);
        }
        setState(R.string.status_vpn_configuration_updated);
    }

    @Override
    public void revert() {
        VpnServiceControls.stop(this.context);
        this.applied.postValue(false);
    }

    @Override
    public boolean isRecordingLogs() {
        return this.recordingLogs;
    }

    @Override
    public void setRecordingLogs(boolean recording) {
        this.recordingLogs = recording;
    }

    @Override
    public List<String> getLogs() {
        return new ArrayList<>(this.logs);
    }

    @Override
    public void clearLogs() {
        this.logs.clear();
    }



    /**
     * Checks host entry related to an host name.
     *
     * @param host A hostname to check.
     * @return The related host entry.
     */
    public HostEntry getEntry(String host) {
        Timber.i("getEntry: host %s", host);


        // Compute miss rate periodically
        this.requestCount++;
        if (this.requestCount >= 1000) {
            int hits = this.blockCache.hitCount();
            int misses = this.blockCache.missCount();
            double missRate = 100D * (hits + misses) / misses;
            Timber.d("Host cache miss rate: %s.", missRate);
            this.requestCount = 0;
        }
        // Add host to logs
        if (this.recordingLogs) {
            if (isBlockedByList(host)) {
                this.logs.add("[-] " + host);
            } else {
                this.logs.add(host);
            }
        }
        // Check cache
        return this.blockCache.get(host);
    }
}
