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


package nl.uva.illc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import nl.uva.illc.dataselection.TranslationTable;


public class AlignmentCalculator {
	
	public static CountDownLatch latch = null;
	public static ExecutorService jobs = Executors.newCachedThreadPool();	

	public static void process(int src[][], int trg[][], TranslationTable t2s, TranslationTable s2t) throws InterruptedException {		
		int split = (int)Math.ceil(src.length / 100000d);
		latch = new CountDownLatch(split);
		for(int start=0;start<src.length;start+=100000) {								
			int end = start+100000;
			if(end > src.length) {
				end = src.length;
			}
			calculateAlignment(src, trg, t2s, s2t, start, end);
		}
		latch.await();				
		jobs.shutdown();
	}
	
	public static void calculateAlignment(final int src[][], final int trg[][], final TranslationTable t2s, final TranslationTable s2t, final int start, final int end) {

		jobs.execute(new Runnable() {
			
			@Override
			public void run() {
				for(int sent=start;sent<end;sent++) {
					int ssent[] = src[sent];
					int tsent[] = trg[sent];
					int a1[] = t2s.getAlignment(ssent, tsent);
					int a2[] = s2t.getAlignment(tsent, ssent);
					List<Alignment> alignments = intersection(a1, a2);
					System.out.println(alignments);
				}		
				
			}
		});
		
	}
	
	public static List<Alignment> intersection(int a1[], int a2[]) {
		List<Alignment> alignments = new ArrayList<Alignment>();
		for(int i=1;i<a1.length;i++) {			
			if(a2[a1[i]]==i) {
				alignments.add(new Alignment(a1[i], i));
			}
		}
		return alignments;
	}	
	
	public static void growDiag(List<Alignment> alignments) {		
		/*for(Alignment alignment : alignments) {
			
		}*/
	}
}

class Alignment {

	public int source;
	public int target;
	
	public Alignment(int source, int target) {
		this.source = source;
		this.target = target;
	}
	
}