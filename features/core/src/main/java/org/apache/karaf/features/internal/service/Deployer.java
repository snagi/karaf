/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.features.internal.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeatureEvent;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.internal.download.DownloadManager;
import org.apache.karaf.features.internal.download.StreamProvider;
import org.apache.karaf.features.internal.region.SubsystemResolver;
import org.apache.karaf.features.internal.util.ChecksumUtils;
import org.apache.karaf.features.internal.util.Macro;
import org.apache.karaf.features.internal.util.MapUtils;
import org.apache.karaf.features.internal.util.MultiException;
import org.apache.karaf.util.collections.CopyOnWriteArrayIdentityList;
import org.eclipse.equinox.region.Region;
import org.eclipse.equinox.region.RegionDigraph;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.felix.resolver.Util.getSymbolicName;
import static org.apache.felix.resolver.Util.getVersion;
import static org.apache.karaf.features.FeaturesService.ROOT_REGION;
import static org.apache.karaf.features.internal.resolver.ResourceUtils.TYPE_SUBSYSTEM;
import static org.apache.karaf.features.internal.resolver.ResourceUtils.getFeatureId;
import static org.apache.karaf.features.internal.resolver.ResourceUtils.getType;
import static org.apache.karaf.features.internal.resolver.ResourceUtils.getUri;
import static org.apache.karaf.features.FeaturesService.UPDATEABLE_URIS;
import static org.apache.karaf.features.FeaturesService.UPDATE_SNAPSHOTS_ALWAYS;
import static org.apache.karaf.features.FeaturesService.UPDATE_SNAPSHOTS_CRC;
import static org.apache.karaf.features.internal.util.MapUtils.add;
import static org.apache.karaf.features.internal.util.MapUtils.addToMapSet;
import static org.apache.karaf.features.internal.util.MapUtils.apply;
import static org.apache.karaf.features.internal.util.MapUtils.copy;
import static org.apache.karaf.features.internal.util.MapUtils.diff;
import static org.apache.karaf.features.internal.util.MapUtils.flatten;
import static org.apache.karaf.features.internal.util.MapUtils.map;
import static org.apache.karaf.features.internal.util.MapUtils.removeFromMapSet;
import static org.osgi.framework.Bundle.ACTIVE;
import static org.osgi.framework.Bundle.RESOLVED;
import static org.osgi.framework.Bundle.STARTING;
import static org.osgi.framework.Bundle.STOPPING;
import static org.osgi.framework.Bundle.STOP_TRANSIENT;
import static org.osgi.framework.Bundle.UNINSTALLED;
import static org.osgi.framework.namespace.IdentityNamespace.IDENTITY_NAMESPACE;
import static org.osgi.framework.namespace.IdentityNamespace.TYPE_BUNDLE;
import static org.osgi.resource.Namespace.CAPABILITY_EFFECTIVE_DIRECTIVE;
import static org.osgi.resource.Namespace.EFFECTIVE_ACTIVE;

public class Deployer {

    public interface DeployCallback {

        void print(String message, boolean verbose);

        void saveState(State state);
        void persistResolveRequest(DeploymentRequest request) throws IOException;
        void installFeatureConfigs(Feature feature) throws IOException, InvalidSyntaxException;
        void callListeners(FeatureEvent featureEvent);

        Bundle installBundle(String region, String uri, InputStream is) throws BundleException;
        void updateBundle(Bundle bundle, InputStream is) throws BundleException;
        void uninstall(Bundle bundle) throws BundleException;
        void startBundle(Bundle bundle) throws BundleException;
        void stopBundle(Bundle bundle, int options) throws BundleException;
        void setBundleStartLevel(Bundle bundle, int startLevel);

        void refreshPackages(Collection<Bundle> bundles) throws InterruptedException;
        void resolveBundles(Set<Bundle> bundles);

        void replaceDigraph(Map<String, Map<String,Map<String,Set<String>>>> policies, Map<String, Set<Long>> bundles) throws BundleException, InvalidSyntaxException;
    }

    public static class PartialDeploymentException extends Exception {
        private final Set<String> missing;

        public PartialDeploymentException(Set<String> missing) {
            this.missing = missing;
        }

        public Set<String> getMissing() {
            return missing;
        }
    }

    static class DeploymentState {
        State state;
        Bundle serviceBundle;
        int initialBundleStartLevel;
        int currentStartLevel;
        Map<Long, Bundle> bundles;
        Map<String, Feature> features;
        Map<String, Set<Long>> bundlesPerRegion;
        Map<String, Map<String, Map<String, Set<String>>>> filtersPerRegion;
    }

    static class DeploymentRequest {
        Set<String> overrides;
        String featureResolutionRange;
        String bundleUpdateRange;
        String updateSnaphots;
        Repository globalRepository;

        Map<String, Set<String>> requirements;
        Map<String, Map<String, FeaturesService.RequestedState>> stateChanges;
        EnumSet<FeaturesService.Option> options;
    }

    static class Deployment {
        Map<Long, Long> bundleChecksums = new HashMap<>();
        Map<Resource, Bundle> resToBnd = new HashMap<>();
        Map<String, RegionDeployment> regions = new HashMap<>();
    }

    static class RegionDeployment {
        List<Resource> toInstall = new ArrayList<>();
        List<Bundle> toDelete = new ArrayList<>();
        Map<Bundle, Resource> toUpdate = new HashMap<>();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Deployer.class);

    private final DownloadManager manager;
    private final DeployCallback callback;

    public Deployer(DownloadManager manager, DeployCallback callback) {
        this.manager = manager;
        this.callback = callback;
    }

    /**
     *
     * @param dstate  deployment state
     * @param request deployment request
     * @throws Exception
     */
    public void deploy(DeploymentState dstate, DeploymentRequest request) throws Exception {

        boolean noRefreshUnmanaged = request.options.contains(FeaturesService.Option.NoAutoRefreshUnmanagedBundles);
        boolean noRefreshManaged = request.options.contains(FeaturesService.Option.NoAutoRefreshManagedBundles);
        boolean noRefresh = request.options.contains(FeaturesService.Option.NoAutoRefreshBundles);
        boolean noStart = request.options.contains(FeaturesService.Option.NoAutoStartBundles);
        boolean verbose = request.options.contains(FeaturesService.Option.Verbose);
        boolean simulate = request.options.contains(FeaturesService.Option.Simulate);
        boolean noManageBundles = request.options.contains(FeaturesService.Option.NoAutoManageBundles);

        // TODO: add an option to unmanage bundles instead of uninstalling those

        Map<String, Set<Long>> managedBundles = copy(dstate.state.managedBundles);

        Map<String, Set<Bundle>> unmanagedBundles = apply(diff(dstate.bundlesPerRegion, dstate.state.managedBundles),
                map(dstate.bundles));

        // Resolve
        SubsystemResolver resolver = new SubsystemResolver(manager);
        resolver.prepare(
                dstate.features.values(),
                request.requirements,
                apply(unmanagedBundles, adapt(BundleRevision.class))
        );
        Set<String> prereqs = resolver.collectPrerequisites();
        if (!prereqs.isEmpty()) {
            for (Iterator<String> iterator = prereqs.iterator(); iterator.hasNext(); ) {
                String prereq = iterator.next();
                String[] parts = prereq.split("/");
                VersionRange range;
                if (parts[1].equals("0.0.0")) {
                    range = VersionRange.ANY_VERSION;
                } else if (!parts[1].startsWith("[") && !parts[1].startsWith("(")) {
                    range = new VersionRange(Macro.transform(request.featureResolutionRange, parts[1]));
                } else {
                    range = new VersionRange(parts[1]);
                }
                boolean found = false;
                for (Set<String> featureSet : dstate.state.installedFeatures.values()) {
                    for (String feature : featureSet) {
                        String[] p = feature.split("/");
                        found = parts[0].equals(p[0]) && range.contains(VersionTable.getVersion(p[1]));
                        if (found) break;
                    }
                    if (found) break;
                }
                if (found) {
                    iterator.remove();
                }
            }
        }
        if (!prereqs.isEmpty()) {
            DeploymentRequest newRequest = new DeploymentRequest();
            newRequest.bundleUpdateRange = request.bundleUpdateRange;
            newRequest.featureResolutionRange = request.featureResolutionRange;
            newRequest.globalRepository = request.globalRepository;
            newRequest.options = request.options;
            newRequest.overrides = request.overrides;
            newRequest.requirements = copy(dstate.state.requirements);
            for (String prereq : prereqs) {
                addToMapSet(newRequest.requirements, ROOT_REGION, prereq);
            }
            newRequest.stateChanges = Collections.emptyMap();
            newRequest.updateSnaphots = request.updateSnaphots;
            deploy(dstate, newRequest);
            throw new PartialDeploymentException(prereqs);
        }

        resolver.resolve(
                request.overrides,
                request.featureResolutionRange,
                request.globalRepository);

        Map<String, StreamProvider> providers = resolver.getProviders();
        Map<String, Set<Resource>> featuresPerRegion = resolver.getFeaturesPerRegions();
        Map<String, Set<String>> installedFeatures = apply(featuresPerRegion, featureId());
        Map<String, Set<String>> newFeatures = diff(installedFeatures, dstate.state.installedFeatures);
        Map<String, Set<String>> delFeatures = diff(dstate.state.installedFeatures, installedFeatures);

        //
        // Compute requested features state
        //
        Map<String, Map<String, String>> stateFeatures = copy(dstate.state.stateFeatures);
        for (Map.Entry<String, Set<String>> entry : delFeatures.entrySet()) {
            Map<String, String> map = stateFeatures.get(entry.getKey());
            if (map != null) {
                map.entrySet().removeAll(entry.getValue());
                if (map.isEmpty()) {
                    stateFeatures.remove(entry.getKey());
                }
            }
        }
        for (Map.Entry<String, Map<String, FeaturesService.RequestedState>> entry1 : request.stateChanges.entrySet()) {
            String region = entry1.getKey();
            Map<String, String> regionStates = stateFeatures.get(region);
            if (regionStates != null) {
                for (Map.Entry<String, FeaturesService.RequestedState> entry2 : entry1.getValue().entrySet()) {
                    String feature = entry2.getKey();
                    if (regionStates.containsKey(feature)) {
                        regionStates.put(feature, entry2.getValue().name());
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : newFeatures.entrySet()) {
            for (String feature : entry.getValue()) {
                Map<String, String> map = stateFeatures.get(entry.getKey());
                if (map == null) {
                    map = new HashMap<>();
                    stateFeatures.put(entry.getKey(), map);
                }
                map.put(feature, noStart ? FeaturesService.RequestedState.Installed.name() : FeaturesService.RequestedState.Started.name());
            }
        }

        // Compute information for each bundle
        Map<String, Map<String, BundleInfo>> bundleInfos = resolver.getBundleInfos();

        //
        // Compute deployment
        //
        Deployer.Deployment deployment = computeDeployment(dstate, request, resolver);

        //
        // Compute the set of bundles to refresh
        //
        Set<Bundle> toRefresh = new TreeSet<>(new BundleComparator()); // sort is only used for display
        for (Deployer.RegionDeployment regionDeployment : deployment.regions.values()) {
            toRefresh.addAll(regionDeployment.toDelete);
            toRefresh.addAll(regionDeployment.toUpdate.keySet());
        }
        if (!noRefreshManaged) {
            computeBundlesToRefresh(toRefresh, dstate.bundles.values(), deployment.resToBnd, resolver.getWiring());
        }
        if (noRefreshUnmanaged) {
            toRefresh.removeAll(flatten(unmanagedBundles));
        }

        // Automatically turn unmanaged bundles into managed bundles
        // if they are required by a feature and no other unmanaged
        // bundles have a requirement on it
        Set<Bundle> toManage = new TreeSet<>(new BundleComparator()); // sort is only used for display
        if (!noManageBundles) {
            Set<Resource> features = resolver.getFeatures().keySet();
            Set<? extends Resource> unmanaged = apply(flatten(unmanagedBundles), adapt(BundleRevision.class));
            Set<Resource> requested = new HashSet<>();
            // Gather bundles required by a feature
            for (List<Wire> wires : resolver.getWiring().values()) {
                for (Wire wire : wires) {
                    if (features.contains(wire.getRequirer()) && unmanaged.contains(wire.getProvider())) {
                        requested.add(wire.getProvider());
                    }
                }
            }
            // Now, we know which bundles are completely unmanaged
            unmanaged.removeAll(requested);
            // Check if bundles have wires from really unmanaged bundles
            for (List<Wire> wires : resolver.getWiring().values()) {
                for (Wire wire : wires) {
                    if (requested.contains(wire.getProvider()) && unmanaged.contains(wire.getRequirer())) {
                        requested.remove(wire.getProvider());
                    }
                }
            }
            if (!requested.isEmpty()) {
                Map<Long, String> bundleToRegion = new HashMap<>();
                for (Map.Entry<String, Set<Long>> entry : dstate.bundlesPerRegion.entrySet()) {
                    for (long id : entry.getValue()) {
                        bundleToRegion.put(id, entry.getKey());
                    }
                }
                for (Resource rev : requested) {
                    Bundle bundle = ((BundleRevision) rev).getBundle();
                    long id = bundle.getBundleId();
                    addToMapSet(managedBundles, bundleToRegion.get(id), id);
                    toManage.add(bundle);
                }
            }
        }

        Set<Bundle> toStart = new HashSet<>();
        Set<Bundle> toResolve = new HashSet<>();
        Set<Bundle> toStop = new HashSet<>();

        //
        // Compute bundle states
        //
        Map<Resource, FeaturesService.RequestedState> states = new HashMap<>();
        for (Map.Entry<String, Set<Resource>> entry : resolver.getFeaturesPerRegions().entrySet()) {
            String region = entry.getKey();
            Map<String, String> fss = stateFeatures.get(region);
            for (Resource feature : entry.getValue()) {
                String fs = fss.get(getFeatureId(feature));
                propagateState(states, feature, FeaturesService.RequestedState.valueOf(fs), resolver);
            }
        }
        states.keySet().retainAll(resolver.getBundles().keySet());
        //
        // Compute bundles to start, stop and resolve
        //
        for (Map.Entry<Resource, FeaturesService.RequestedState> entry : states.entrySet()) {
            Bundle bundle = deployment.resToBnd.get(entry.getKey());
            if (bundle != null) {
                switch (entry.getValue()) {
                case Started:
                    toResolve.add(bundle);
                    toStart.add(bundle);
                    break;
                case Resolved:
                    toResolve.add(bundle);
                    toStop.add(bundle);
                    break;
                }
            }
        }
        //
        // Compute bundle all start levels and start levels to update
        //
        Map<Resource, Integer> startLevels = new HashMap<>();
        Map<Bundle, Integer> toUpdateStartLevel = new HashMap<>();
        for (Map.Entry<String, Set<Resource>> entry : resolver.getBundlesPerRegions().entrySet()) {
            String region = entry.getKey();
            for (Resource resource : entry.getValue()) {
                BundleInfo bi = bundleInfos.get(region).get(getUri(resource));
                if (bi != null) {
                    int sl = bi.getStartLevel() > 0 ? bi.getStartLevel() : dstate.initialBundleStartLevel;
                    startLevels.put(resource, sl);
                    Bundle bundle = deployment.resToBnd.get(resource);
                    if (bundle != null) {
                        int curSl = bundle.adapt(BundleStartLevel.class).getStartLevel();
                        if (sl != curSl) {
                            toUpdateStartLevel.put(bundle, sl);
                            if (sl > dstate.currentStartLevel) {
                                toStop.add(bundle);
                            }
                        }
                    }
                }
            }
        }

        //
        // Log deployment
        //
        logDeployment(deployment, verbose);

        if (simulate) {
            if (!noRefresh && !toRefresh.isEmpty()) {
                print("  Bundles to refresh:", verbose);
                for (Bundle bundle : toRefresh) {
                    print("    " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                }
            }
            if (!toManage.isEmpty()) {
                print("  Managing bundle:", verbose);
                for (Bundle bundle : toManage) {
                    print("    " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                }
            }
            return;
        }

        //
        // Execute deployment
        //
        // #1: stop bundles that needs to be updated or uninstalled in order
        // #2: uninstall needed bundles
        // #3: update regions
        // #4: update bundles
        // #5: install bundles
        // #6: save state
        // #7: install configuration
        // #8: refresh bundles
        // #9: start bundles in order
        // #10: send events
        //


        //
        // Handle updates on the FeaturesService bundle
        //
        Deployer.RegionDeployment rootRegionDeployment = deployment.regions.get(ROOT_REGION);
        // We don't support uninstalling the bundle
        if (rootRegionDeployment != null && rootRegionDeployment.toDelete.contains(dstate.serviceBundle)) {
            throw new UnsupportedOperationException("Uninstalling the FeaturesService bundle is not supported");
        }
        // If the bundle needs to be updated, do the following:
        //  - persist the request to indicate the resolution must be continued after restart
        //  - update the checksum and save the state
        //  - compute bundles wired to the FeaturesService bundle that will be refreshed
        //  - stop the bundle
        //  - update the bundle
        //  - refresh wired bundles
        //  - start the bundle
        //  - exit
        // When restarting, the resolution will be attempted again
        if (rootRegionDeployment != null && rootRegionDeployment.toUpdate.containsKey(dstate.serviceBundle)) {
            callback.persistResolveRequest(request);
            // If the bundle is updated because of a different checksum,
            // save the new checksum persistently
            if (deployment.bundleChecksums.containsKey(dstate.serviceBundle.getBundleId())) {
                State state = dstate.state.copy();
                state.bundleChecksums.put(dstate.serviceBundle.getBundleId(),
                                          deployment.bundleChecksums.get(dstate.serviceBundle.getBundleId()));
                callback.saveState(state);
            }
            Resource resource = rootRegionDeployment.toUpdate.get(dstate.serviceBundle);
            String uri = getUri(resource);
            print("The FeaturesService bundle needs is being updated with " + uri, verbose);
            toRefresh.clear();
            toRefresh.add(dstate.serviceBundle);
            computeBundlesToRefresh(toRefresh,
                    dstate.bundles.values(),
                    Collections.<Resource, Bundle>emptyMap(),
                    Collections.<Resource, List<Wire>>emptyMap());
            callback.stopBundle(dstate.serviceBundle, STOP_TRANSIENT);
            try (
                    InputStream is = getBundleInputStream(resource, providers)
            ) {
                callback.updateBundle(dstate.serviceBundle, is);
            }
            callback.refreshPackages(toRefresh);
            callback.startBundle(dstate.serviceBundle);
            return;
        }

        //
        // Perform bundle operations
        //

        //
        // Stop bundles by chunks
        //
        for (Deployer.RegionDeployment regionDeployment : deployment.regions.values()) {
            toStop.addAll(regionDeployment.toUpdate.keySet());
            toStop.addAll(regionDeployment.toDelete);
        }
        removeFragmentsAndBundlesInState(toStop, UNINSTALLED | RESOLVED | STOPPING);
        if (!toStop.isEmpty()) {
            print("Stopping bundles:", verbose);
            while (!toStop.isEmpty()) {
                List<Bundle> bs = getBundlesToStop(toStop);
                for (Bundle bundle : bs) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                    // If the bundle start level will be changed, stop it persistently to
                    // avoid a restart when the start level is actually changed
                    callback.stopBundle(bundle, toUpdateStartLevel.containsKey(bundle) ? 0 : STOP_TRANSIENT);
                    toStop.remove(bundle);
                }
            }
        }

        //
        // Delete bundles
        //
        boolean hasToDelete = false;
        for (Deployer.RegionDeployment regionDeployment : deployment.regions.values()) {
            if (hasToDelete = !regionDeployment.toDelete.isEmpty()) {
                break;
            }
        }
        if (hasToDelete) {
            print("Uninstalling bundles:", verbose);
            for (Map.Entry<String, Deployer.RegionDeployment> entry : deployment.regions.entrySet()) {
                String name = entry.getKey();
                Deployer.RegionDeployment regionDeployment = entry.getValue();
                for (Bundle bundle : regionDeployment.toDelete) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                    callback.uninstall(bundle);
                    removeFromMapSet(managedBundles, name, bundle.getBundleId());
                }
            }
        }

        //
        // Update regions
        //
        {
            // Add bundles
            Map<String, Set<Long>> bundles = new HashMap<>();
            add(bundles, apply(unmanagedBundles, bundleId()));
            add(bundles, managedBundles);
            // Compute policies
            RegionDigraph computedDigraph = resolver.getFlatDigraph();
            Map<String, Map<String, Map<String, Set<String>>>> policies = copy(dstate.filtersPerRegion);
            // Only keep regions which still have bundles
            policies.keySet().retainAll(bundles.keySet());
            // Fix broken filters
            for (String name : policies.keySet()) {
                policies.get(name).keySet().retainAll(policies.keySet());
            }
            // Update managed regions
            for (Region computedRegion : computedDigraph.getRegions()) {
                String name = computedRegion.getName();
                Map<String, Map<String, Set<String>>> policy = policies.get(name);
                if (policy == null) {
                    policy = new HashMap<>();
                    policies.put(name, policy);
                }
                for (RegionDigraph.FilteredRegion fr : computedRegion.getEdges()) {
                    String r2 = fr.getRegion().getName();
                    Map<String, Set<String>> filters = new HashMap<>();
                    Map<String, Collection<String>> current = fr.getFilter().getSharingPolicy();
                    for (String ns : current.keySet()) {
                        for (String f : current.get(ns)) {
                            addToMapSet(filters, ns, f);
                        }
                    }
                    policy.put(r2, filters);
                }
            }
            // Apply all changes
            callback.replaceDigraph(policies, bundles);
        }


        //
        // Update bundles
        //
        boolean hasToUpdate = false;
        for (Deployer.RegionDeployment regionDeployment : deployment.regions.values()) {
            if (hasToUpdate = !regionDeployment.toUpdate.isEmpty()) {
                break;
            }
        }
        if (hasToUpdate) {
            print("Updating bundles:", verbose);
            for (Map.Entry<String, Deployer.RegionDeployment> rde : deployment.regions.entrySet()) {
                for (Map.Entry<Bundle, Resource> entry : rde.getValue().toUpdate.entrySet()) {
                    Bundle bundle = entry.getKey();
                    Resource resource = entry.getValue();
                    String uri = getUri(resource);
                    print("  " + uri, verbose);
                    try (
                            InputStream is = getBundleInputStream(resource, providers)
                    ) {
                        callback.updateBundle(bundle, is);
                    }
                    toStart.add(bundle);
                }
            }
        }
        //
        // Update start levels
        //
        for (Map.Entry<Bundle, Integer> entry : toUpdateStartLevel.entrySet()) {
            Bundle bundle = entry.getKey();
            int sl = entry.getValue();
            callback.setBundleStartLevel(bundle, sl);
        }
        //
        // Install bundles
        //
        boolean hasToInstall = false;
        for (Deployer.RegionDeployment regionDeployment : deployment.regions.values()) {
            if (hasToInstall = !regionDeployment.toInstall.isEmpty()) {
                break;
            }
        }
        if (hasToInstall) {
            print("Installing bundles:", verbose);
            for (Map.Entry<String, Deployer.RegionDeployment> entry : deployment.regions.entrySet()) {
                String name = entry.getKey();
                Deployer.RegionDeployment regionDeployment = entry.getValue();
                for (Resource resource : regionDeployment.toInstall) {
                    String uri = getUri(resource);
                    print("  " + uri, verbose);
                    Bundle bundle;
                    long crc;
                    try (
                            ChecksumUtils.CRCInputStream is = new ChecksumUtils.CRCInputStream(getBundleInputStream(resource, providers))
                    ) {
                        bundle = callback.installBundle(name, uri, is);
                        crc = is.getCRC();
                    }
                    addToMapSet(managedBundles, name, bundle.getBundleId());
                    deployment.resToBnd.put(resource, bundle);
                    // save a checksum of installed snapshot bundle
                    if (UPDATE_SNAPSHOTS_CRC.equals(request.updateSnaphots)
                            && isUpdateable(resource) && !deployment.bundleChecksums.containsKey(bundle.getBundleId())) {
                        deployment.bundleChecksums.put(bundle.getBundleId(), crc);
                    }
                    int startLevel = startLevels.get(resource);
                    if (startLevel != dstate.initialBundleStartLevel) {
                        callback.setBundleStartLevel(bundle, startLevel);
                    }
                    FeaturesService.RequestedState reqState = states.get(resource);
                    if (reqState == null) {
                        reqState = FeaturesService.RequestedState.Started;
                    }
                    switch (reqState) {
                    case Started:
                        toResolve.add(bundle);
                        toStart.add(bundle);
                        break;
                    case Resolved:
                        toResolve.add(bundle);
                        break;
                    }
                }
            }
        }

        //
        // Update and save state
        //
        State newState = new State();
        newState.bundleChecksums.putAll(deployment.bundleChecksums);
        newState.requirements.putAll(request.requirements);
        newState.installedFeatures.putAll(installedFeatures);
        newState.stateFeatures.putAll(stateFeatures);
        newState.managedBundles.putAll(managedBundles);
        callback.saveState(newState);

        //
        // Install configurations
        //
        if (!newFeatures.isEmpty()) {
            Set<Feature> set = apply(flatten(newFeatures), map(dstate.features));
            for (Feature feature : set) {
                callback.installFeatureConfigs(feature);
            }
        }

        // TODO: remove this hack, but it avoids loading the class after the bundle is refreshed
        new CopyOnWriteArrayIdentityList().iterator();
        RequirementSort.sort(Collections.<Resource>emptyList());

        if (!noRefresh) {
            toStop = new HashSet<>();
            toStop.addAll(toRefresh);
            removeFragmentsAndBundlesInState(toStop, UNINSTALLED | RESOLVED | STOPPING);
            if (!toStop.isEmpty()) {
                print("Stopping bundles:", verbose);
                while (!toStop.isEmpty()) {
                    List<Bundle> bs = getBundlesToStop(toStop);
                    for (Bundle bundle : bs) {
                        print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                        callback.stopBundle(bundle, STOP_TRANSIENT);
                        toStop.remove(bundle);
                        toStart.add(bundle);
                    }
                }
            }

            if (!toRefresh.isEmpty()) {
                print("Refreshing bundles:", verbose);
                for (Bundle bundle : toRefresh) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                }
                if (!toRefresh.isEmpty()) {
                    callback.refreshPackages(toRefresh);
                }
            }
        }

        // Resolve bundles
        toResolve.addAll(toStart);
        toResolve.addAll(toRefresh);
        removeFragmentsAndBundlesInState(toResolve, UNINSTALLED);
        callback.resolveBundles(toResolve);

        // Compute bundles to start
        removeFragmentsAndBundlesInState(toStart, UNINSTALLED | ACTIVE | STARTING);
        if (!toStart.isEmpty()) {
            // Compute correct start order
            List<Exception> exceptions = new ArrayList<>();
            print("Starting bundles:", verbose);
            while (!toStart.isEmpty()) {
                List<Bundle> bs = getBundlesToStart(toStart, dstate.serviceBundle);
                for (Bundle bundle : bs) {
                    print("  " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                    try {
                        callback.startBundle(bundle);
                    } catch (BundleException e) {
                        exceptions.add(e);
                    }
                    toStart.remove(bundle);
                }
            }
            if (!exceptions.isEmpty()) {
                throw new MultiException("Error restarting bundles", exceptions);
            }
        }

        // Call listeners
        for (Map.Entry<String, Set<String>> entry : delFeatures.entrySet()) {
            for (String name : entry.getValue()) {
                Feature feature = dstate.features.get(name);
                if (feature != null) {
                    callback.callListeners(new FeatureEvent(FeatureEvent.EventType.FeatureUninstalled, feature, entry.getKey(), false));
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : newFeatures.entrySet()) {
            for (String name : entry.getValue()) {
                Feature feature = dstate.features.get(name);
                if (feature != null) {
                    callback.callListeners(new FeatureEvent(FeatureEvent.EventType.FeatureInstalled, feature, entry.getKey(), false));
                }
            }
        }

        print("Done.", verbose);
    }

    private void propagateState(Map<Resource, FeaturesService.RequestedState> states, Resource resource, FeaturesService.RequestedState state, SubsystemResolver resolver) {
        if (!isSubsystem(resource)) {
            FeaturesService.RequestedState reqState = mergeStates(state, states.get(resource));
            if (reqState != states.get(resource)) {
                states.put(resource, reqState);
                for (Wire wire : resolver.getWiring().get(resource)) {
                    Resource provider = wire.getProvider();
                    FeaturesService.RequestedState stateToMerge;
                    String region = resolver.getBundles().get(provider);
                    BundleInfo bi = region != null ? resolver.getBundleInfos().get(region).get(getUri(provider)) : null;
                    if (reqState == FeaturesService.RequestedState.Started) {
                        String effective = wire.getCapability().getDirectives().get(CAPABILITY_EFFECTIVE_DIRECTIVE);
                        // If there is an active effective capability or a requirement from the feature
                        // and if the bundle is flagged as to start, start it
                        if ((EFFECTIVE_ACTIVE.equals(effective) || IDENTITY_NAMESPACE.equals(wire.getCapability().getNamespace()))
                                && (bi == null || bi.isStart())) {
                            stateToMerge = FeaturesService.RequestedState.Started;
                        } else {
                            stateToMerge = FeaturesService.RequestedState.Resolved;
                        }
                    } else {
                        stateToMerge = reqState;
                    }
                    propagateState(states, provider, stateToMerge, resolver);
                }
            }
        }
    }

    private boolean isSubsystem(Resource resource) {
        return TYPE_SUBSYSTEM.equals(getType(resource));
    }

    private boolean isBundle(Resource resource) {
        return TYPE_BUNDLE.equals(getType(resource));
    }

    private FeaturesService.RequestedState mergeStates(FeaturesService.RequestedState s1, FeaturesService.RequestedState s2) {
        if (s1 == FeaturesService.RequestedState.Started || s2 == FeaturesService.RequestedState.Started) {
            return FeaturesService.RequestedState.Started;
        }
        if (s1 == FeaturesService.RequestedState.Resolved || s2 == FeaturesService.RequestedState.Resolved) {
            return FeaturesService.RequestedState.Resolved;
        }
        return FeaturesService.RequestedState.Installed;
    }

    private void computeBundlesToRefresh(Set<Bundle> toRefresh, Collection<Bundle> bundles, Map<Resource, Bundle> resources, Map<Resource, List<Wire>> resolution) {
        int size;
        do {
            size = toRefresh.size();
            for (Bundle bundle : bundles) {
                // Continue if we already know about this bundle
                if (toRefresh.contains(bundle)) {
                    continue;
                }
                // Ignore non resolved bundle
                BundleWiring wiring = bundle.adapt(BundleWiring.class);
                if (wiring == null) {
                    continue;
                }
                // Get through the old resolution and flag this bundle
                // if it was wired to a bundle to be refreshed
                for (BundleWire wire : wiring.getRequiredWires(null)) {
                    if (toRefresh.contains(wire.getProvider().getBundle())) {
                        toRefresh.add(bundle);
                        break;
                    }
                }
                // Get through the new resolution and flag this bundle
                // if it's wired to any new bundle
                List<Wire> newWires = resolution.get(wiring.getRevision());
                if (newWires != null) {
                    for (Wire wire : newWires) {
                        if (!isBundle(wire.getProvider())) {
                            continue;
                        }
                        Bundle b;
                        if (wire.getProvider() instanceof BundleRevision) {
                            b = ((BundleRevision) wire.getProvider()).getBundle();
                        } else {
                            b = resources.get(wire.getProvider());
                        }
                        if (b == null || toRefresh.contains(b)) {
                            toRefresh.add(bundle);
                            break;
                        }
                    }
                }
            }
        } while (toRefresh.size() > size);
    }

    private void print(String message, boolean verbose) {
        callback.print(message, verbose);
    }

    private void removeFragmentsAndBundlesInState(Collection<Bundle> bundles, int state) {
        for (Iterator<Bundle> iterator = bundles.iterator(); iterator.hasNext();) {
            Bundle bundle = iterator.next();
            if ((bundle.getState() & state) != 0
                    || bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null) {
                iterator.remove();
            }
        }
    }

    protected void logDeployment(Deployer.Deployment overallDeployment, boolean verbose) {
        if (overallDeployment.regions.isEmpty()) {
            print("No deployment change.", verbose);
            return;
        }
        print("Changes to perform:", verbose);
        for (Map.Entry<String, Deployer.RegionDeployment> region : overallDeployment.regions.entrySet()) {
            Deployer.RegionDeployment deployment = region.getValue();
            print("  Region: " + region.getKey(), verbose);
            if (!deployment.toDelete.isEmpty()) {
                print("    Bundles to uninstall:", verbose);
                for (Bundle bundle : deployment.toDelete) {
                    print("      " + bundle.getSymbolicName() + " / " + bundle.getVersion(), verbose);
                }
            }
            if (!deployment.toUpdate.isEmpty()) {
                print("    Bundles to update:", verbose);
                for (Map.Entry<Bundle, Resource> entry : deployment.toUpdate.entrySet()) {
                    print("      " + entry.getKey().getSymbolicName() + " / " + entry.getKey().getVersion() + " with " + getUri(entry.getValue()), verbose);
                }
            }
            if (!deployment.toInstall.isEmpty()) {
                print("    Bundles to install:", verbose);
                for (Resource resource : deployment.toInstall) {
                    print("      " + getUri(resource), verbose);
                }
            }
        }
    }

    protected Deployment computeDeployment(
                    DeploymentState dstate,
                    DeploymentRequest request,
                    SubsystemResolver resolver) throws IOException {

        Deployment result = new Deployment();

        Map<String, Set<Resource>> bundlesPerRegions = resolver.getBundlesPerRegions();

        // Gather all regions, including old ones and new ones
        Set<String> regions = new HashSet<>();
        regions.addAll(dstate.state.managedBundles.keySet());
        regions.addAll(bundlesPerRegions.keySet());

        for (String region : regions) {

            Deployer.RegionDeployment deployment = new Deployer.RegionDeployment();

            // Get the list of bundles currently assigned in the region
            Set<Long> managed = dstate.state.managedBundles.get(region);
            if (managed == null) {
                managed = Collections.emptySet();
            }

            // Compute the list of resources to deploy in the region
            Set<Resource> bundlesInRegion = bundlesPerRegions.get(region);
            List<Resource> toDeploy = bundlesInRegion != null
                    ? new ArrayList<>(bundlesInRegion) : new ArrayList<Resource>();

            // First pass: go through all installed bundles and mark them
            // as either to ignore or delete
            for (long bundleId : managed) {
                // Look for the installed bundle
                Bundle bundle = dstate.bundles.get(bundleId);
                // Bundle has been manually uninstalled ?
                if (bundle != null) {
                    // Look for a matching resource
                    Resource resource = null;
                    for (Resource res : toDeploy) {
                        if (bundle.getSymbolicName().equals(getSymbolicName(res))
                                && bundle.getVersion().equals(getVersion(res))) {
                            resource = res;
                            break;
                        }
                    }
                    // We found a matching bundle
                    if (resource != null) {
                        // In case of snapshots, check if the snapshot is out of date
                        // and flag it as to update
                        if (isUpdateable(resource)) {
                            // Always update snapshots
                            if (UPDATE_SNAPSHOTS_ALWAYS.equalsIgnoreCase(request.updateSnaphots)) {
                                LOGGER.debug("Update snapshot for " + bundle.getLocation());
                                deployment.toUpdate.put(bundle, resource);
                            } else if (UPDATE_SNAPSHOTS_CRC.equalsIgnoreCase(request.updateSnaphots)) {
                                // if the checksum are different
                                try (
                                        InputStream is = getBundleInputStream(resource, resolver.getProviders())
                                ) {
                                    long newCrc = ChecksumUtils.checksum(is);
                                    long oldCrc = dstate.state.bundleChecksums.containsKey(bundle.getBundleId())
                                                    ? dstate.state.bundleChecksums.get(bundle.getBundleId()) : 0L;
                                    if (newCrc != oldCrc) {
                                        LOGGER.debug("New snapshot available for " + bundle.getLocation());
                                        deployment.toUpdate.put(bundle, resource);
                                    }
                                    result.bundleChecksums.put(bundle.getBundleId(), newCrc);
                                }
                            }
                        }
                        // We're done for this resource
                        toDeploy.remove(resource);
                        result.resToBnd.put(resource, bundle);
                        // There's no matching resource
                        // If the bundle is managed, we need to delete it
                    } else if (managed.contains(bundle.getBundleId())) {
                        deployment.toDelete.add(bundle);
                    }
                }
            }

            // Second pass on remaining resources
            for (Resource resource : toDeploy) {
                TreeMap<Version, Bundle> matching = new TreeMap<>();
                VersionRange range = new VersionRange(Macro.transform(request.bundleUpdateRange, getVersion(resource).toString()));
                for (Bundle bundle : deployment.toDelete) {
                    if (bundle.getSymbolicName().equals(getSymbolicName(resource)) && range.contains(bundle.getVersion())) {
                        matching.put(bundle.getVersion(), bundle);
                    }
                }
                if (!matching.isEmpty()) {
                    Bundle bundle = matching.lastEntry().getValue();
                    deployment.toUpdate.put(bundle, resource);
                    deployment.toDelete.remove(bundle);
                    result.resToBnd.put(resource, bundle);
                } else {
                    deployment.toInstall.add(resource);
                }
            }
            Collections.sort(deployment.toInstall, new ResourceComparator());

            // Add this region if there is something to do
            if (!deployment.toDelete.isEmpty()
                    || !deployment.toUpdate.isEmpty()
                    || !deployment.toInstall.isEmpty()) {
                result.regions.put(region, deployment);
            }
        }

        return result;
    }

    protected <T> MapUtils.Function<Bundle, T> adapt(final Class<T> clazz) {
        return new MapUtils.Function<Bundle, T>() {
            @Override
            public T apply(Bundle bundle) {
                return bundle.adapt(clazz);
            }
        };
    }

    protected MapUtils.Function<Bundle, Long> bundleId() {
        return new MapUtils.Function<Bundle, Long>() {
            @Override
            public Long apply(Bundle bundle) {
                return bundle.getBundleId();
            }
        };
    }

    protected MapUtils.Function<Resource, String> featureId() {
        return new MapUtils.Function<Resource, String>() {
            @Override
            public String apply(Resource resource) {
                return getFeatureId(resource);
            }
        };
    }

    protected boolean isUpdateable(Resource resource) {
        String uri = getUri(resource);
        return uri.matches(UPDATEABLE_URIS);
    }

    protected List<Bundle> getBundlesToStart(Collection<Bundle> bundles, Bundle serviceBundle) {
        // Restart the features service last, regardless of any other consideration
        // so that we don't end up with the service trying to do stuff before we're done
        boolean restart = false;

        SortedMap<Integer, Set<Bundle>> bundlesPerStartLevel = new TreeMap<>();
        for (Bundle bundle : bundles) {
            if (bundle == serviceBundle) {
                restart = true;
            } else {
                int sl = bundle.adapt(BundleStartLevel.class).getStartLevel();
                addToMapSet(bundlesPerStartLevel, sl, bundle);
            }
        }
        if (bundlesPerStartLevel.isEmpty()) {
            bundles = Collections.emptyList();
        } else {
            bundles = bundlesPerStartLevel.remove(bundlesPerStartLevel.firstKey());
        }

        // We hit FELIX-2949 if we don't use the correct order as Felix resolver isn't greedy.
        // In order to minimize that, we make sure we resolve the bundles in the order they
        // are given back by the resolution, meaning that all root bundles (i.e. those that were
        // not flagged as dependencies in features) are started before the others.   This should
        // make sure those important bundles are started first and minimize the problem.
        List<BundleRevision> revs = new ArrayList<>();
        for (Bundle bundle : bundles) {
            revs.add(bundle.adapt(BundleRevision.class));
        }
        List<Bundle> sorted = new ArrayList<>();
        for (BundleRevision rev : RequirementSort.sort(revs)) {
            sorted.add(rev.getBundle());
        }
        if (sorted.isEmpty() && restart) {
            sorted.add(serviceBundle);
        }
        return sorted;
    }

    protected List<Bundle> getBundlesToStop(Collection<Bundle> bundles) {
        SortedMap<Integer, Set<Bundle>> bundlesPerStartLevel = new TreeMap<>();
        for (Bundle bundle : bundles) {
            int sl = bundle.adapt(BundleStartLevel.class).getStartLevel();
            addToMapSet(bundlesPerStartLevel, sl, bundle);
        }
        bundles = bundlesPerStartLevel.get(bundlesPerStartLevel.lastKey());

        List<Bundle> bundlesToDestroy = new ArrayList<>();
        for (Bundle bundle : bundles) {
            ServiceReference[] references = bundle.getRegisteredServices();
            int usage = 0;
            if (references != null) {
                for (ServiceReference reference : references) {
                    usage += getServiceUsage(reference, bundles);
                }
            }
            LOGGER.debug("Usage for bundle {} is {}", bundle, usage);
            if (usage == 0) {
                bundlesToDestroy.add(bundle);
            }
        }
        if (!bundlesToDestroy.isEmpty()) {
            Collections.sort(bundlesToDestroy, new Comparator<Bundle>() {
                public int compare(Bundle b1, Bundle b2) {
                    return (int) (b2.getLastModified() - b1.getLastModified());
                }
            });
            LOGGER.debug("Selected bundles {} for destroy (no services in use)", bundlesToDestroy);
        } else {
            ServiceReference ref = null;
            for (Bundle bundle : bundles) {
                ServiceReference[] references = bundle.getRegisteredServices();
                for (ServiceReference reference : references) {
                    if (getServiceUsage(reference, bundles) == 0) {
                        continue;
                    }
                    if (ref == null || reference.compareTo(ref) < 0) {
                        LOGGER.debug("Currently selecting bundle {} for destroy (with reference {})", bundle, reference);
                        ref = reference;
                    }
                }
            }
            if (ref != null) {
                bundlesToDestroy.add(ref.getBundle());
            }
            LOGGER.debug("Selected bundle {} for destroy (lowest ranking service)", bundlesToDestroy);
        }
        return bundlesToDestroy;
    }

    private static int getServiceUsage(ServiceReference ref, Collection<Bundle> bundles) {
        Bundle[] usingBundles = ref.getUsingBundles();
        int nb = 0;
        if (usingBundles != null) {
            for (Bundle bundle : usingBundles) {
                if (bundles.contains(bundle)) {
                    nb++;
                }
            }
        }
        return nb;
    }

    protected InputStream getBundleInputStream(Resource resource, Map<String, StreamProvider> providers) throws IOException {
        String uri = getUri(resource);
        if (uri == null) {
            throw new IllegalStateException("Resource has no uri");
        }
        StreamProvider provider = providers.get(uri);
        if (provider == null) {
            throw new IllegalStateException("Resource " + uri + " has no StreamProvider");
        }
        return provider.open();
    }

}
