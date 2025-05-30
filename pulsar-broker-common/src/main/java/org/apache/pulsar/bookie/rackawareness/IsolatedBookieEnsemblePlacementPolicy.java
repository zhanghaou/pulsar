/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.bookie.rackawareness;

import static org.apache.pulsar.bookie.rackawareness.BookieRackAffinityMapping.METADATA_STORE_INSTANCE;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import io.netty.util.HashedWheelTimer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.client.RackawareEnsemblePlacementPolicy;
import org.apache.bookkeeper.client.RackawareEnsemblePlacementPolicyImpl;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.feature.FeatureProvider;
import org.apache.bookkeeper.meta.exceptions.MetadataException;
import org.apache.bookkeeper.net.BookieId;
import org.apache.bookkeeper.net.DNSToSwitchMapping;
import org.apache.bookkeeper.proto.BookieAddressResolver;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.common.policies.data.BookieInfo;
import org.apache.pulsar.common.policies.data.BookiesRackConfiguration;
import org.apache.pulsar.common.policies.data.EnsemblePlacementPolicyConfig;
import org.apache.pulsar.metadata.api.MetadataCache;
import org.apache.pulsar.metadata.api.MetadataStore;

@Slf4j
public class IsolatedBookieEnsemblePlacementPolicy extends RackawareEnsemblePlacementPolicy {
    public static final String ISOLATION_BOOKIE_GROUPS = "isolationBookieGroups";
    public static final String SECONDARY_ISOLATION_BOOKIE_GROUPS = "secondaryIsolationBookieGroups";

    // Using a pair to save the isolation groups, the left value is the primary group and the right value is
    // the secondary group.
    private ImmutablePair<Set<String>, Set<String>> defaultIsolationGroups;

    private MetadataCache<BookiesRackConfiguration> bookieMappingCache;

    private static final String PULSAR_SYSTEM_TOPIC_ISOLATION_GROUP = "*";

    private volatile BookiesRackConfiguration cachedRackConfiguration = null;

    public IsolatedBookieEnsemblePlacementPolicy() {
        super();
    }

    @Override
    public RackawareEnsemblePlacementPolicyImpl initialize(ClientConfiguration conf,
            Optional<DNSToSwitchMapping> optionalDnsResolver, HashedWheelTimer timer, FeatureProvider featureProvider,
            StatsLogger statsLogger, BookieAddressResolver bookieAddressResolver) {
        MetadataStore store;
        try {
            store = BookieRackAffinityMapping.getMetadataStore(conf);
        } catch (MetadataException e) {
            throw new RuntimeException(METADATA_STORE_INSTANCE + " failed initialized");
        }
        Set<String> primaryIsolationGroups = new HashSet<>();
        Set<String> secondaryIsolationGroups = new HashSet<>();
        if (conf.getProperty(ISOLATION_BOOKIE_GROUPS) != null) {
            String isolationGroupsString = ConfigurationStringUtil
                    .castToString(conf.getProperty(ISOLATION_BOOKIE_GROUPS));
            if (!isolationGroupsString.isEmpty()) {
                Collections.addAll(primaryIsolationGroups, isolationGroupsString.split(","));
            }
            // Only add the bookieMappingCache if we have defined an isolation group
            bookieMappingCache = store.getMetadataCache(BookiesRackConfiguration.class);
            bookieMappingCache.get(BookieRackAffinityMapping.BOOKIE_INFO_ROOT_PATH).thenAccept(opt -> opt.ifPresent(
                            bookiesRackConfiguration -> cachedRackConfiguration = bookiesRackConfiguration))
                    .exceptionally(e -> {
                        log.warn("Failed to load bookies rack configuration while initialize the PlacementPolicy.");
                        return null;
                    });
        }
        if (conf.getProperty(SECONDARY_ISOLATION_BOOKIE_GROUPS) != null) {
            String secondaryIsolationGroupsString = ConfigurationStringUtil
                    .castToString(conf.getProperty(SECONDARY_ISOLATION_BOOKIE_GROUPS));
            if (!secondaryIsolationGroupsString.isEmpty()) {
                Collections.addAll(secondaryIsolationGroups, secondaryIsolationGroupsString.split(","));
            }
        }
        defaultIsolationGroups = ImmutablePair.of(primaryIsolationGroups, secondaryIsolationGroups);
        return super.initialize(conf, optionalDnsResolver, timer, featureProvider, statsLogger, bookieAddressResolver);
    }

    @Override
    public PlacementResult<List<BookieId>> newEnsemble(int ensembleSize, int writeQuorumSize, int ackQuorumSize,
            Map<String, byte[]> customMetadata, Set<BookieId> excludeBookies)
            throws BKNotEnoughBookiesException {
        if (excludeBookies == null) {
            excludeBookies = new HashSet<>();
        }
        excludeBookies.addAll(getExcludedBookies(ensembleSize, customMetadata));
        return super.newEnsemble(ensembleSize, writeQuorumSize, ackQuorumSize, customMetadata, excludeBookies);
    }

    @Override
    public PlacementResult<BookieId> replaceBookie(int ensembleSize, int writeQuorumSize, int ackQuorumSize,
            Map<String, byte[]> customMetadata, List<BookieId> currentEnsemble,
            BookieId bookieToReplace, Set<BookieId> excludeBookies)
            throws BKNotEnoughBookiesException {
        if (excludeBookies == null) {
            excludeBookies = new HashSet<>();
        }
        excludeBookies.addAll(getExcludedBookies(ensembleSize, customMetadata));
        return super.replaceBookie(ensembleSize, writeQuorumSize, ackQuorumSize, customMetadata, currentEnsemble,
                bookieToReplace, excludeBookies);
    }

    private Set<BookieId> getExcludedBookies(int ensembleSize, Map<String, byte[]> customMetadata){
        // parse the ensemble placement policy from the custom metadata, if it is present, we will apply it to
        // the isolation groups for filtering the bookies.
        Optional<EnsemblePlacementPolicyConfig> ensemblePlacementPolicyConfig =
                getEnsemblePlacementPolicyConfig(customMetadata);
        Set<BookieId> excludedBookies;
        if (ensemblePlacementPolicyConfig.isPresent()) {
            EnsemblePlacementPolicyConfig config = ensemblePlacementPolicyConfig.get();
            Pair<Set<String>, Set<String>> groups = getIsolationGroup(config);
            excludedBookies = getExcludedBookiesWithIsolationGroups(ensembleSize, groups);
        } else {
            excludedBookies = getExcludedBookiesWithIsolationGroups(ensembleSize, defaultIsolationGroups);
        }
        return excludedBookies;
    }

    private static Optional<EnsemblePlacementPolicyConfig> getEnsemblePlacementPolicyConfig(
        Map<String, byte[]> customMetadata) {

        byte[] ensemblePlacementPolicyConfigData = customMetadata.get(
            EnsemblePlacementPolicyConfig.ENSEMBLE_PLACEMENT_POLICY_CONFIG);
        if (ensemblePlacementPolicyConfigData != null) {
            try {
                return Optional.ofNullable(EnsemblePlacementPolicyConfig.decode(ensemblePlacementPolicyConfigData));
            } catch (EnsemblePlacementPolicyConfig.ParseEnsemblePlacementPolicyConfigException e) {
                log.error("Failed to parse the ensemble placement policy config from the custom metadata", e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Pair<Set<String>, Set<String>> getIsolationGroup(
            EnsemblePlacementPolicyConfig ensemblePlacementPolicyConfig) {
        MutablePair<Set<String>, Set<String>> pair = new MutablePair<>();
        String className = IsolatedBookieEnsemblePlacementPolicy.class.getName();
        if (ensemblePlacementPolicyConfig.getPolicyClass().getName().equals(className)) {
            Map<String, Object> properties = ensemblePlacementPolicyConfig.getProperties();
            String primaryIsolationGroupString = ConfigurationStringUtil
                    .castToString(properties.getOrDefault(ISOLATION_BOOKIE_GROUPS, ""));
            String secondaryIsolationGroupString = ConfigurationStringUtil
                    .castToString(properties.getOrDefault(SECONDARY_ISOLATION_BOOKIE_GROUPS, ""));
            if (!primaryIsolationGroupString.isEmpty()) {
                pair.setLeft(Sets.newHashSet(primaryIsolationGroupString.split(",")));
            } else {
                pair.setLeft(Collections.emptySet());
            }
            if (!secondaryIsolationGroupString.isEmpty()) {
                pair.setRight(Sets.newHashSet(secondaryIsolationGroupString.split(",")));
            } else {
                pair.setRight(Collections.emptySet());
            }
        }
        return pair;
    }

    @VisibleForTesting
    Set<BookieId> getExcludedBookiesWithIsolationGroups(int ensembleSize,
        Pair<Set<String>, Set<String>> isolationGroups) {
        Set<BookieId> excludedBookies = new HashSet<>();
        if (isolationGroups != null && isolationGroups.getLeft().contains(PULSAR_SYSTEM_TOPIC_ISOLATION_GROUP)) {
            return excludedBookies;
        }
        try {
            if (bookieMappingCache != null) {
                bookieMappingCache.get(BookieRackAffinityMapping.BOOKIE_INFO_ROOT_PATH)
                        .thenAccept(opt -> cachedRackConfiguration = opt.orElse(null)).exceptionally(e -> {
                            log.warn("Failed to update the newest bookies rack config.");
                            return null;
                        });

                BookiesRackConfiguration allGroupsBookieMapping = cachedRackConfiguration;
                if (allGroupsBookieMapping == null) {
                    log.debug("The bookies rack config is not available at now.");
                    return excludedBookies;
                }
                Set<String> allGroups = allGroupsBookieMapping.keySet();
                if (allGroups.isEmpty()) {
                    return excludedBookies;
                }
                int totalAvailableBookiesInPrimaryGroup = 0;
                Set<String> primaryIsolationGroup = Collections.emptySet();
                Set<String> secondaryIsolationGroup = Collections.emptySet();
                Set<BookieId> primaryGroupBookies = new HashSet<>();
                if (isolationGroups != null) {
                    primaryIsolationGroup = isolationGroups.getLeft();
                    secondaryIsolationGroup = isolationGroups.getRight();
                }
                for (String group : allGroups) {
                    Set<String> bookiesInGroup = allGroupsBookieMapping.get(group).keySet();
                    if (!primaryIsolationGroup.contains(group)) {
                        for (String bookieAddress : bookiesInGroup) {
                            excludedBookies.add(BookieId.parse(bookieAddress));
                        }
                    } else {
                        for (String groupBookie : bookiesInGroup) {
                            BookieId bookieId = BookieId.parse(groupBookie);
                            if (primaryGroupBookies.add(bookieId)) {
                                totalAvailableBookiesInPrimaryGroup += knownBookies.containsKey(bookieId) ? 1 : 0;
                            }
                        }
                    }
                }

                Set<BookieId> otherGroupBookies = new HashSet<>(excludedBookies);
                Set<BookieId> nonRegionBookies  = new HashSet<>(knownBookies.keySet());
                nonRegionBookies.removeAll(primaryGroupBookies);
                excludedBookies.addAll(nonRegionBookies);

                // sometime while doing isolation, user might not want to remove isolated bookies from other default
                // groups. so, same set of bookies could be overlapped into isolated-group and other default groups. so,
                // try to remove those overlapped bookies from excluded-bookie list because they are also part of
                // isolated-group bookies.
                for (String group : primaryIsolationGroup) {
                    Map<String, BookieInfo> bookieGroup = allGroupsBookieMapping.get(group);
                    if (bookieGroup != null && !bookieGroup.isEmpty()) {
                        for (String bookieAddress : bookieGroup.keySet()) {
                            excludedBookies.remove(BookieId.parse(bookieAddress));
                        }
                    }
                }
                // if primary-isolated-bookies are not enough then add consider secondary isolated bookie group as well.
                int totalAvailableBookiesFromPrimaryAndSecondary = totalAvailableBookiesInPrimaryGroup;
                if (totalAvailableBookiesInPrimaryGroup < ensembleSize) {
                    log.info(
                        "Not found enough available-bookies from primary isolation group [{}], checking secondary "
                                + "group [{}]", primaryIsolationGroup, secondaryIsolationGroup);
                    for (String group : secondaryIsolationGroup) {
                        Map<String, BookieInfo> bookieGroup = allGroupsBookieMapping.get(group);
                        if (bookieGroup != null && !bookieGroup.isEmpty()) {
                            for (String bookieAddress : bookieGroup.keySet()) {
                                if (excludedBookies.remove(BookieId.parse(bookieAddress))) {
                                    totalAvailableBookiesFromPrimaryAndSecondary += 1;
                                }
                            }
                        }
                    }
                }
                if (totalAvailableBookiesFromPrimaryAndSecondary < ensembleSize) {
                    log.info(
                            "Not found enough available-bookies from primary isolation group [{}] and secondary "
                                    + "isolation group [{}], checking from non-region bookies",
                            primaryIsolationGroup, secondaryIsolationGroup);
                    nonRegionBookies.removeAll(otherGroupBookies);
                    for (BookieId bookie: nonRegionBookies) {
                        excludedBookies.remove(bookie);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error getting bookie isolation info from metadata store: {}", e.getMessage());
        }
        return excludedBookies;
    }
}
