/*
 * Copyright (C) 2013 Maciej G�rski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.mg6.android.maps.extensions.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.mg6.android.maps.extensions.Marker;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.util.SparseArray;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

class GridClusteringStrategy implements ClusteringStrategy {

	private GoogleMap provider;
	private Map<DelegatingMarker, ClusterMarker> markers;
	private double clusterSize;

	private SparseArray<ClusterMarker> clusters;

	private Set<ClusterMarker> refreshQueue = new HashSet<ClusterMarker>();
	private boolean refreshPending;
	private Handler refresher = new Handler(new Callback() {
		public boolean handleMessage(Message msg) {
			for (ClusterMarker cluster : refreshQueue) {
				cluster.refresh();
			}
			refreshQueue.clear();
			refreshPending = false;
			return true;
		}
	});

	public GridClusteringStrategy(GoogleMap provider, List<DelegatingMarker> markers) {
		this.provider = provider;
		this.markers = new HashMap<DelegatingMarker, ClusterMarker>();
		for (DelegatingMarker m : markers) {
			this.markers.put(m, null);
		}
		this.clusterSize = calculateClusterSize(provider.getCameraPosition().zoom);
		recalculate();
	}

	@Override
	public void cleanup() {
		if (clusters != null) {
			for (int i = 0; i < clusters.size(); i++) {
				ClusterMarker cluster = clusters.valueAt(i);
				cluster.cleanup();
			}
			clusters = null;
		}
		for (DelegatingMarker marker : markers.keySet()) {
			if (marker.isVisible()) {
				marker.changeVisible(true);
			}
		}
		refresher.removeMessages(0);
	}

	@Override
	public void onZoomChange(float zoom) {
		double clusterSize = calculateClusterSize(zoom);
		if (this.clusterSize != clusterSize) {
			this.clusterSize = clusterSize;
			recalculate();
		}
	}

	@Override
	public void onAdd(DelegatingMarker marker) {
		addMarker(marker, true);
	}

	private void addMarker(DelegatingMarker marker, boolean refresh) {
		LatLng position = marker.getPosition();
		int clusterId = calculateClusterId(position);
		ClusterMarker cluster = findClusterById(clusterId);
		cluster.add(marker);
		markers.put(marker, cluster);
		if (refresh && marker.isVisible()) {
			refresh(cluster);
		}
	}

	@Override
	public void onRemove(DelegatingMarker marker) {
		ClusterMarker cluster = markers.remove(marker);
		cluster.remove(marker);
		refresh(cluster);
	}

	@Override
	public void onPositionChange(DelegatingMarker marker) {
		ClusterMarker oldCluster = markers.get(marker);
		if (isMarkerInCluster(marker, oldCluster)) {
			if (marker.isVisible()) {
				refresh(oldCluster);
			}
		} else {
			oldCluster.remove(marker);
			refresh(oldCluster);
			addMarker(marker, true);
		}
	}

	@Override
	public Marker map(com.google.android.gms.maps.model.Marker original) {
		if (clusters != null) {
			for (int i = 0; i < clusters.size(); i++) {
				ClusterMarker cluster = clusters.valueAt(i);
				if (original.equals(cluster.getVirtual())) {
					return cluster;
				}
			}
		}
		return null;
	}

	private boolean isMarkerInCluster(DelegatingMarker marker, ClusterMarker cluster) {
		int clusterId = cluster.getClusterId();
		int markerClusterId = calculateClusterId(marker.getPosition());
		return clusterId == markerClusterId;
	}

	private ClusterMarker findClusterById(int clusterId) {
		ClusterMarker cluster = clusters.get(clusterId);
		if (cluster == null) {
			cluster = new ClusterMarker(clusterId, provider);
			clusters.put(clusterId, cluster);
		}
		return cluster;
	}

	@Override
	public void onVisibilityChangeRequest(DelegatingMarker marker, boolean visible) {
		ClusterMarker cluster = markers.get(marker);
		refresh(cluster);
	}

	private void refresh(ClusterMarker cluster) {
		refreshQueue.add(cluster);
		if (!refreshPending) {
			refresher.sendEmptyMessage(0);
			refreshPending = true;
		}
	}

	private void recalculate() {
		if (clusters != null) {
			for (int i = 0; i < clusters.size(); i++) {
				ClusterMarker cluster = clusters.valueAt(i);
				cluster.cleanup();
			}
			clusters = null;
			for (DelegatingMarker marker : markers.keySet()) {
				markers.put(marker, null);
			}
		}
		if (clusterSize == 0.0) {
			for (DelegatingMarker marker : markers.keySet()) {
				if (marker.isVisible()) {
					marker.changeVisible(true);
				}
			}
		} else {
			clusters = new SparseArray<ClusterMarker>();
			for (DelegatingMarker marker : markers.keySet()) {
				addMarker(marker, false);
			}
			for (int i = 0; i < clusters.size(); i++) {
				ClusterMarker cluster = clusters.valueAt(i);
				refresh(cluster);
			}
		}
	}

	private int calculateClusterId(LatLng position) {
		int y = (int) ((position.latitude + 180.0) / clusterSize);
		int x = (int) ((position.longitude + 90.0) / clusterSize);
		return (y << 16) + x;
	}

	private double calculateClusterSize(float zoom) {
		return (1 << ((int) (23.5f - zoom))) / 100000.0;
	}
}