/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.kubernetes.v2.caching.view.provider;

import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.caching.Keys;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.caching.agent.KubernetesCacheDataConverter;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.caching.view.model.KubernetesV2Manifest;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesPodMetric;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourceProperties;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.KubernetesResourcePropertyRegistry;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesKind;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.description.manifest.KubernetesManifest;
import com.netflix.spinnaker.clouddriver.kubernetes.v2.op.handler.KubernetesHandler;
import com.netflix.spinnaker.clouddriver.model.ManifestProvider;
import com.netflix.spinnaker.moniker.Moniker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KubernetesV2ManifestProvider implements ManifestProvider<KubernetesV2Manifest> {
  private final KubernetesResourcePropertyRegistry registry;
  private final KubernetesCacheUtils cacheUtils;

  @Autowired
  public KubernetesV2ManifestProvider(KubernetesResourcePropertyRegistry registry, KubernetesCacheUtils cacheUtils) {
    this.registry = registry;
    this.cacheUtils = cacheUtils;
  }

  @Override
  public KubernetesV2Manifest getManifest(String account, String location, String name) {
    Pair<KubernetesKind, String> parsedName;
    try {
      parsedName = KubernetesManifest.fromFullResourceName(name);
    } catch (Exception e) {
      return null;
    }

    KubernetesKind kind = parsedName.getLeft();
    if (!kind.isNamespaced() && StringUtils.isNotEmpty(location)) {
      log.warn("Kind {} is not namespaced, but namespace {} was provided (ignoring)", kind, location);
      location = "";
    }

    String key = Keys.infrastructure(
        kind,
        account,
        location,
        parsedName.getRight()
    );

    Optional<CacheData> dataOptional = cacheUtils.getSingleEntry(kind.toString(), key);
    if (!dataOptional.isPresent()) {
      return null;
    }

    CacheData data = dataOptional.get();
    KubernetesResourceProperties properties = registry.get(account, kind);
    if (properties == null) {
      return null;
    }

    Function<KubernetesManifest, String> lastEventTimestamp = (m) -> (String) m.getOrDefault("lastTimestamp", m.getOrDefault("firstTimestamp", "n/a"));

    List<KubernetesManifest> events = cacheUtils.getTransitiveRelationship(kind.toString(), Collections.singletonList(key), KubernetesKind.EVENT.toString())
        .stream()
        .map(KubernetesCacheDataConverter::getManifest)
        .sorted(Comparator.comparing(lastEventTimestamp))
        .collect(Collectors.toList());

    String metricKey = Keys.metric(kind, account, location, parsedName.getRight());
    List<Map> metrics = cacheUtils.getSingleEntry(Keys.Kind.KUBERNETES_METRIC.toString(), metricKey)
        .map(KubernetesCacheDataConverter::getMetrics)
        .orElse(Collections.emptyList());

    KubernetesHandler handler = properties.getHandler();

    KubernetesManifest manifest = KubernetesCacheDataConverter.getManifest(data);
    Moniker moniker = KubernetesCacheDataConverter.getMoniker(data);

    return new KubernetesV2Manifest().builder()
        .account(account)
        .location(location)
        .manifest(manifest)
        .moniker(moniker)
        .status(handler.status(manifest))
        .artifacts(handler.listArtifacts(manifest))
        .events(events)
        .warnings(handler.listWarnings(manifest))
        .metrics(metrics)
        .build();
  }
}
