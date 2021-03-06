/*
 * (C) Copyright 2015 ILLC University of Amsterdam (http://www.illc.uva.nl)
 * 
 * This work was supported by "STW Open Technologieprogramma" grant
 * under project name "Data-Powered Domain-Specific Translation Services On Demand" 
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details. 
 *
 */

package nl.uva.illc.dataselection;

import net.openhft.koloboke.collect.map.hash.HashIntFloatMap;
import net.openhft.koloboke.collect.map.hash.HashIntFloatMaps;
import net.openhft.koloboke.collect.map.hash.HashIntObjMap;
import net.openhft.koloboke.collect.map.hash.HashIntObjMaps;

public class TranslationTable {

	public HashIntObjMap<HashIntFloatMap> ttable = null;

	public TranslationTable() {
		ttable = HashIntObjMaps.newMutableMap();
	}

	public TranslationTable(TranslationTable toCopy) {
		this.ttable = HashIntObjMaps.newMutableMap();
		this.ttable.putAll(toCopy.ttable);
	}

	public void put(int tw, int sw, float value) {
		HashIntFloatMap tMap = ttable.get(tw);
		if (tMap == null) {
			tMap = HashIntFloatMaps.newMutableMap();
			ttable.put(tw, tMap);
		}
		tMap.put(sw, value);
	}

	public void increas(int tw, int sw, float value) {
		HashIntFloatMap tMap = ttable.get(tw);
		if (tMap == null) {
			tMap = HashIntFloatMaps.newMutableMap();
			ttable.put(tw, tMap);
		}
		tMap.addValue(sw, value, 0f);
	}

	public float get(int tw, int sw) {
		HashIntFloatMap tMap = ttable.get(tw);
		if (tMap != null) {
			if (tMap.containsKey(sw)) {
				return tMap.get(sw);
			}
		}
		return Float.NaN;
	}

	public float get(int tw, int sw, float d) {
		float value = get(tw, sw);
		return Float.isNaN(value) ? d : value;
	}

	public void remove(int tw, int sw) {
		HashIntFloatMap tMap = ttable.get(tw);
		if (tMap != null) {
			tMap.remove(sw);
		}
	}

	public void normalize() {
		for (int tw : ttable.keySet()) {
			HashIntFloatMap tMap = ttable.get(tw);
			float sum = 0;
			for (int sw : tMap.keySet()) {
				sum += tMap.get(sw);
			}
			for (int sw : tMap.keySet()) {
				tMap.put(sw, tMap.get(sw) / sum);
			}
		}
	}

	public int[] getAlignment(int ssent[], int tsent[]) {
		int alignments[] = new int[tsent.length];
		for (int t = 1; t < tsent.length; t++) {
			int tw = tsent[t];
			float max_p = 0f;
			int ind = -1;
			for (int s = 0; s < ssent.length; s++) {
				int sw = ssent[s];
				float p = this.get(tw, sw, 0f);
				if (p >= max_p) {
					max_p = p;
					ind = s;
				}
			}
			alignments[t] = ind;
		}
		return alignments;
	}

}
