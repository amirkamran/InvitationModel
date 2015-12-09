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

import edu.berkeley.nlp.lm.ConfigOptions;
import edu.berkeley.nlp.lm.NgramLanguageModel;
import edu.berkeley.nlp.lm.StringWordIndexer;
import edu.berkeley.nlp.lm.io.ArpaLmReader;
import edu.berkeley.nlp.lm.io.LmReaders;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.openhft.koloboke.collect.map.hash.HashIntFloatMap;
import net.openhft.koloboke.collect.map.hash.HashIntFloatMaps;
import net.openhft.koloboke.collect.map.hash.HashIntIntMap;
import net.openhft.koloboke.collect.map.hash.HashIntIntMaps;
import net.openhft.koloboke.collect.map.hash.HashIntObjMap;
import net.openhft.koloboke.collect.map.hash.HashIntObjMaps;
import net.openhft.koloboke.collect.map.hash.HashObjIntMap;
import net.openhft.koloboke.collect.map.hash.HashObjIntMaps;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Invitation based data selection approach exploits in-domain data (both
 * monolingual and bilingual) as prior to guide word alignment and phrase pair
 * estimates in the large mix-domain corpus. As a by-product, accurate estimates
 * for P(D|e,f) of the mixed-domain sentences are produced (with D being either
 * in-domain or out-of-domain), which can be used to rank the sentences in Dmix
 * according to their relevance to Din.
 * 
 * For more information see: Hoang, Cuong and Sima'an, Khalil (2014): Latent
 * Domain Translation Models in Mix-of-Domains Haystack, Proceedings of COLING
 * 2014, the 25th International Conference on Computational Linguistics
 * http://www.aclweb.org/anthology/C14-1182.pdf
 * 
 * @author Amir Kamran
 */

public class InvitationModel {

	private static Logger log = LogManager.getLogger(InvitationModel.class);

	static String IN = null;
	static String MIX = null;
	static String SRC = null;
	static String TRG = null;

	static int iMAX = 10;

	static int src_indomain[][] = null;
	static int trg_indomain[][] = null;
	static int src_mixdomain[][] = null;
	static int trg_mixdomain[][] = null;
	static int src_outdomain[][] = null;
	static int trg_outdomain[][] = null;
	
	static long indomain_token_count = 0;

	static HashObjIntMap<String> src_codes = null;
	static HashObjIntMap<String> trg_codes = null;
	
	static float lm[][] = null;

	static float LOG_0_5 = (float) Math.log(0.5);
		
	// default confidence threshold: use to decide which sentences
	// will update the translation table 
	static float CONF_THRESHOLD = (float) Math.log(0);
	
	// default convergence threshold: How much change in PD1 is significant
	// to continue to next iteration
	static float CONV_THRESHOLD = 0f;
	

	static float PD1 = LOG_0_5;
	static float PD0 = LOG_0_5;

	static TranslationTable ttable[] = new TranslationTable[4];

	public static CountDownLatch latch = null;
	public static ExecutorService jobs = Executors.newCachedThreadPool();

	public static HashIntIntMap ignore = HashIntIntMaps.newMutableMap();

	public static float n = (float)Math.log(0.3);
	public static float V = (float)Math.log(100000);
	public static float nV = n + V;
	public static float p = - nV;

	public static void main(String args[]) throws InterruptedException {
		
		try{
			log.info("Start ...");
			
			processCommandLineArguments(args);
			readFiles();
			
			V = (float)Math.log(Math.max(src_codes.size(), trg_codes.size()));
			nV = n + V;
			p = - nV;
			
			initialize();
			burnIN();
			createLM();
			training();
	
		} catch(Exception e) {			
			log.error(e);
		} finally {
			jobs.shutdown();			
			jobs.awaitTermination(10, TimeUnit.MINUTES);	
			log.info("END");			
		}
	}

	public static void processCommandLineArguments(String args[]) {
		Options options = new Options();
		options.addOption("cmix", "mix-domain-corpus", true,
				"Mix-domain corpus name");
		options.addOption("cin", "in-domain-corpus", true,
				"In-domain corpus name");
		options.addOption("src", "src-language", true, "Source Language");
		options.addOption("trg", "trg-language", true, "Target Language");
		options.addOption("i", "max-iterations", true, "Maximum Iterations");
		options.addOption("th", "threshold", true, "This threshold deicdes which sentences updates translation tables. Default is 0.5");
		options.addOption("cf", "conv_threshold", true, "This threshold decide if the convergence is reached. Default is 0.00001");		

		CommandLineParser parser = new GnuParser();
		try {
			CommandLine cmd = parser.parse(options, args);
			if (cmd.hasOption("cmix") && cmd.hasOption("cin")
					&& cmd.hasOption("src") && cmd.hasOption("trg")) {
				MIX = cmd.getOptionValue("cmix");
				IN = cmd.getOptionValue("cin");
				SRC = cmd.getOptionValue("src");
				TRG = cmd.getOptionValue("trg");

				if (cmd.hasOption("i")) {
					iMAX = Integer.parseInt(cmd.getOptionValue("i"));
				}
				
				if (cmd.hasOption("th")) {
					CONF_THRESHOLD = (float) Math.log(Double.parseDouble(cmd.getOptionValue("th")));
				}
				
				if (cmd.hasOption("cf")) {
					CONV_THRESHOLD = (float) Float.parseFloat(cmd.getOptionValue("cf"));
				}
				

			} else {
				System.out.println("Missing required argumetns!");
				printHelp(options);
			}
		} catch (ParseException e) {
			printHelp(options);
		}
	}

	private static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("java " + InvitationModel.class.getName(), options);
		System.exit(1);
	}

	public static void initialize() throws InterruptedException {

		log.info("Initializing Translaiton Tables");

		for (int i = 0; i < ttable.length; i++) {
			ttable[i] = new TranslationTable();
		}

		latch = new CountDownLatch(2);

		initializeTranslationTable(src_indomain, trg_indomain, ttable[0]);
		initializeTranslationTable(trg_indomain, src_indomain, ttable[1]);
		initializeTranslationTable(src_mixdomain, trg_mixdomain, ttable[2]);
		initializeTranslationTable(trg_mixdomain, src_mixdomain, ttable[3]);

		latch.await();

		log.info("DONE");
	}

	public static void initializeTranslationTable(final int src[][],
			final int trg[][], final TranslationTable ttable) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {

				HashIntFloatMap totals = HashIntFloatMaps.newMutableMap();

				for (int sent = 0; sent < src.length; sent++) {

					if (sent % 100000 == 0)
						log.debug("Sentence " + sent);

					int ssent[] = src[sent];
					int tsent[] = trg[sent];
					for (int t = 1; t < tsent.length; t++) {
						int tw = tsent[t];
						for (int s = 0; s < ssent.length; s++) {
							int sw = ssent[s];
							ttable.increas(tw, sw, 1f);
							totals.addValue(sw, 1f, 0f);							
						}
					}
				}

				// normalizing and smoothing
				for (int tw : ttable.ttable.keySet()) {
					HashIntFloatMap tMap = ttable.ttable.get(tw);
					for (int sw : tMap.keySet()) {
						float prob = logAdd((float)Math.log(ttable.get(tw, sw)), n) - logAdd((float)Math.log(totals.get(sw)), nV);
						ttable.put(tw, sw, prob);
					}
				}

				log.info(".");

				InvitationModel.latch.countDown();
			}

		});

	}

	public static void createLM() throws InterruptedException {

		log.info("Creating Language Models ...");
		
		lm = new float[4][];
		
		latch = new CountDownLatch(4);

		createLM(IN + "." + SRC + ".encoded", lm, 0, src_mixdomain);
		createLM(IN + "." + TRG + ".encoded", lm, 1, trg_mixdomain);
		createLM("outdomain." + SRC + ".encoded", lm, 2, src_mixdomain);
		createLM("outdomain." + TRG + ".encoded", lm, 3, trg_mixdomain);

		latch.await();

		log.info("DONE");

	}

	public static void burnIN() throws IOException, InterruptedException {

		log.info("BurnIN started ... ");

		HashIntObjMap<Result> results = null;

		for (int i = 1; i <= 1; i++) {

			log.info("Iteration " + i);

			results = HashIntObjMaps.newMutableMap();

			float sPD[][] = new float[2][src_mixdomain.length];

			int splits = 50;
			int split_size = src_mixdomain.length / splits;

			latch = new CountDownLatch(splits);
			for (int s=0;s<splits;s++) {
				int start = s*split_size;
				int end   = start + split_size;
				if (s==(splits-1)) {
					end = src_mixdomain.length;
				}
				calcualteBurnInScore(start, end, sPD);
			}
			latch.await();
			

			//float countPD[] = new float[2];
			//countPD[0] = Float.NEGATIVE_INFINITY;
			//countPD[1] = Float.NEGATIVE_INFINITY;

			for (int sent = 0; sent < src_mixdomain.length; sent++) {

				if (ignore.containsKey(sent))
					continue;

				if (Float.isNaN(sPD[0][sent]) || Float.isNaN(sPD[1][sent])) {
					ignore.put(sent, sent);
					log.info("Ignoring " + (sent + 1));
					continue;
				}

				//countPD[0] = logAdd(countPD[0], sPD[0][sent]);
				//countPD[1] = logAdd(countPD[1], sPD[1][sent]);
				
				results.put(sent, new Result(sent, sPD[0][sent]));

			}
			
			//PD1 = countPD[1] - logAdd(countPD[0], countPD[1]);
			//PD0 = countPD[0] - logAdd(countPD[0], countPD[1]);
						
		}
		
		latch = new CountDownLatch(1);
		ArrayList<Result> sortedResult = new ArrayList<Result>(results.values());
		Collections.sort(sortedResult);
		writeOutdomain(sortedResult);
		latch.await();
		
		log.info("BurnIN DONE");

	}

	public static void training() throws FileNotFoundException,
			InterruptedException {

		log.info("Starting Invitation EM ...");
		
		PD1 = LOG_0_5;
		PD0 = LOG_0_5;
		ttable[0] = new TranslationTable();
		ttable[1] = new TranslationTable();				
		ttable[2] = new TranslationTable();
		ttable[3] = new TranslationTable();		
		
		latch = new CountDownLatch(4);
		initializeTranslationTable(src_indomain, trg_indomain, ttable[0]);
		initializeTranslationTable(trg_indomain, src_indomain, ttable[1]);		
		initializeTranslationTable(src_outdomain, trg_outdomain, ttable[2]);
		initializeTranslationTable(trg_outdomain, src_outdomain, ttable[3]);
		latch.await();

		for (int i = 1; i <= iMAX; i++) {
			log.info("Iteration " + i);
			HashIntObjMap<Result> results = HashIntObjMaps.newMutableMap();

			float sPD[][] = new float[2][src_mixdomain.length];

			int splits = 50;
			int split_size = src_mixdomain.length / splits;

			latch = new CountDownLatch(splits);
			for (int s=0;s<splits;s++) {
				int start = s*split_size;
				int end   = start + split_size;
				if (s==(splits-1)) {
					end = src_mixdomain.length;
				}
				calcualteScore(start, end, sPD);
			}
			latch.await();

			float countPD[] = new float[2];
			countPD[0] = Float.NEGATIVE_INFINITY;
			countPD[1] = Float.NEGATIVE_INFINITY;

			for (int sent = 0; sent < src_mixdomain.length; sent++) {

				if (ignore.containsKey(sent))
					continue;

				if (Float.isNaN(sPD[0][sent]) || Float.isNaN(sPD[1][sent])) {
					ignore.put(sent, sent);
					log.info("Ignoring " + (sent + 1));
					continue;
				}

				countPD[0] = logAdd(countPD[0], sPD[0][sent]);
				countPD[1] = logAdd(countPD[1], sPD[1][sent]);

				float srcP = lm[0][sent];
				float trgP = lm[1][sent];
				results.put(sent, new Result(sent, sPD[1][sent], srcP + trgP));

			}

			float newPD1 = countPD[1] - logAdd(countPD[0], countPD[1]);
			float newPD0 = countPD[0] - logAdd(countPD[0], countPD[1]);

			log.info("PD1 ~ PD0 " + Math.exp(newPD1) + " ~ " + Math.exp(newPD0));
			
			writeResult(i, results);
			
			if(i>1 && CONV_THRESHOLD!=0 && Math.abs(Math.exp(newPD1) - Math.exp(PD1)) <= CONV_THRESHOLD) {
				log.info("Convergence threshold reached.");
				break;
			}
			
			PD1 = newPD1;
			PD0 = newPD0;

			if (i < iMAX) {
				
				latch = new CountDownLatch(4);
				updateTranslationTable(src_mixdomain, trg_mixdomain, src_indomain, trg_indomain, ttable[0], sPD[1], (float)Math.log(1));
				updateTranslationTable(trg_mixdomain, src_mixdomain, trg_indomain, src_indomain, ttable[1], sPD[1], (float)Math.log(1));

				updateTranslationTable(src_mixdomain, trg_mixdomain, ttable[2], sPD[0]);
				updateTranslationTable(trg_mixdomain, src_mixdomain, ttable[3], sPD[0]);
				latch.await();
			}
			
			// Reinitialize the language models and translation tables
			
			/*if(i==1) {
				latch = new CountDownLatch(1);
				ArrayList<Result> sortedResult = new ArrayList<Result>(results.values());
				Collections.sort(sortedResult);	
				Collections.reverse(sortedResult);	
				writeOutdomain(sortedResult);
				latch.await();
				latch = new CountDownLatch(6);
				ttable[0] = new TranslationTable();
				ttable[1] = new TranslationTable();				
				ttable[2] = new TranslationTable();
				ttable[3] = new TranslationTable();
				initializeTranslationTable(src_indomain, trg_indomain, ttable[0]);
				initializeTranslationTable(trg_indomain, src_indomain, ttable[1]);				
				initializeTranslationTable(src_outdomain, trg_outdomain, ttable[2]);
				initializeTranslationTable(trg_outdomain, src_outdomain, ttable[3]);
				createLM("outdomain." + SRC + ".encoded", lm, 2, src_mixdomain);
				createLM("outdomain." + TRG + ".encoded", lm, 3, trg_mixdomain);				
				latch.await();
				
				PD1 = LOG_0_5;
				PD0 = LOG_0_5;
			}*/

		}
	}

	public static void calcualteScore(final int start, final int end,
			final float sPD[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				for (int sent = start; sent < end; sent++) {

					if (ignore.containsKey(sent))
						continue;

					int ssent[] = src_mixdomain[sent];
					int tsent[] = trg_mixdomain[sent];

					float sProb[] = new float[4];

					sProb[0] = calculateProb(ssent, tsent, ttable[0]);
					sProb[1] = calculateProb(tsent, ssent, ttable[1]);
					sProb[2] = calculateProb(ssent, tsent, ttable[2]);
					sProb[3] = calculateProb(tsent, ssent, ttable[3]);

					float in_score  = PD1 + logAdd(sProb[0] + lm[1][sent], sProb[1] + lm[0][sent]);
					float mix_score = PD0 + logAdd(sProb[2] + lm[3][sent], sProb[3] + lm[2][sent]);

					sPD[1][sent] = in_score  - logAdd(in_score, mix_score);
					sPD[0][sent] = mix_score - logAdd(in_score, mix_score);

				}
				InvitationModel.latch.countDown();
			}
		});

	}

	public static void calcualteBurnInScore(final int start, final int end,
			final float sPD[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				for (int sent = start; sent < end; sent++) {

					if (ignore.containsKey(sent))
						continue;

					int ssent[] = src_mixdomain[sent];
					int tsent[] = trg_mixdomain[sent];

					float sProb[] = new float[4];

					sProb[0] = calculateProb(ssent, tsent, ttable[0]);
					sProb[1] = calculateProb(tsent, ssent, ttable[1]);
					sProb[2] = calculateProb(ssent, tsent, ttable[2]);
					sProb[3] = calculateProb(tsent, ssent, ttable[3]);

					float in_score  = PD1 + logAdd(sProb[0], sProb[1]);
					float mix_score = PD0 + logAdd(sProb[2], sProb[3]);

					sPD[1][sent] = in_score  - logAdd(in_score, mix_score);
					sPD[0][sent] = mix_score - logAdd(in_score, mix_score);

				}
				InvitationModel.latch.countDown();
			}
		});

	}
	
	public static void writeOutdomain(final ArrayList<Result> results) {
		
		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Writing outdomain corpus ... ");

				PrintWriter src_out;
				PrintWriter trg_out;
				PrintWriter out_score;
				
				try {
					src_out = new PrintWriter("outdomain." + SRC + ".encoded");
					trg_out = new PrintWriter("outdomain." + TRG + ".encoded");
					out_score = new PrintWriter("outdomain.scores");
					
					long outdomain_token_count = 0;
					int outdomain_size = 0;
					
					for (Result r : results) {
						
						int sentIndex = r.sentenceNumber - 1;
	
						int ssent[] = src_mixdomain[sentIndex];
						outdomain_size ++;
						outdomain_token_count += ssent.length;
						
						if (outdomain_token_count >= indomain_token_count) {
							break;
						}
						

					}					
					
					src_outdomain = new int[outdomain_size][];
					trg_outdomain = new int[outdomain_size][];

					int j = 0;
	
					for (Result r : results) {
	
						int sentIndex = r.sentenceNumber - 1;
	
						int ssent[] = src_mixdomain[sentIndex];
						int tsent[] = trg_mixdomain[sentIndex];
						
						outdomain_token_count += ssent.length;
	
						out_score.println(r.sentenceNumber + "\t" + Math.exp(r.score));
	
						if(j<outdomain_size) {
						
							src_outdomain[j] = ssent;
							trg_outdomain[j] = tsent;
		
							for (int w = 1; w < ssent.length; w++) {
								src_out.print(ssent[w]);
								src_out.print(" ");
							}
							src_out.println();
							for (int w = 1; w < tsent.length; w++) {
								trg_out.print(tsent[w]);
								trg_out.print(" ");
							}
							trg_out.println();
						}
	
						j++;
												
						
					}
	
					out_score.close();
	
					src_out.close();
					trg_out.close();
				} catch (IOException e) {
					e.printStackTrace();
				}				

				InvitationModel.latch.countDown();
				log.info("DONE");				
			}
			
		});
		
	}

	public static void writeResult(final int iterationNumber,
			final HashIntObjMap<Result> results) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				ArrayList<Result> sortedResult = new ArrayList<Result>(results.values());
				Collections.sort(sortedResult);
				try {
					PrintWriter output = new PrintWriter("output_" + iterationNumber + ".txt");
					for (Result r : sortedResult) {
						output.println(r.sentenceNumber + "\t"
								+ Math.exp(r.score) + "\t"
								+ Math.exp(r.lm_score));
					}
					output.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});

	}

	public static float calculateProb(final int ssent[], final int tsent[],
			final TranslationTable ttable) {
		float prob = 0;
		for (int t = 1; t < tsent.length; t++) {
			int tw = tsent[t];
			float sum = Float.NEGATIVE_INFINITY;
			for (int s = 0; s < ssent.length; s++) {
				int sw = ssent[s];
				sum = logAdd(sum, ttable.get(tw, sw, p));
			}
			prob += sum;
		}
		return prob;
	}

	public static void updateTranslationTable(final int src[][],
			final int trg[][], final TranslationTable ttable, final float sPD[]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Updating translation table ... ");

				TranslationTable counts = new TranslationTable();
				HashIntFloatMap totals = HashIntFloatMaps.newMutableMap();

				// collect counts
				InvitationModel.collectCounts(src, trg, ttable, sPD, counts, totals);

				// maximization
				for (int tw : counts.ttable.keySet()) {
					HashIntFloatMap tMap = counts.ttable.get(tw);
					for (int sw : tMap.keySet()) {
						float newProb = counts.get(tw, sw) - totals.get(sw);
						ttable.put(tw, sw, newProb);
					}
				}
				log.info("Updating translation table DONE");
				InvitationModel.latch.countDown();
			}

		});

	}
	
	public static void collectCounts(int src[][], int trg[][], TranslationTable ttable, float sPD[], TranslationTable counts, HashIntFloatMap totals) {
		
		for (int sent = 0; sent < src.length; sent++) {
			if (sent % 100000 == 0)
				log.debug("Sentence " + sent);

			if (ignore.containsKey(sent))
				continue;
			
			if(sPD[sent] < CONF_THRESHOLD) continue;

			int ssent[] = src[sent];
			int tsent[] = trg[sent];

			HashIntFloatMap s_total = HashIntFloatMaps.newMutableMap();

			// calculating normalization
			for (int t = 1; t < tsent.length; t++) {
				int tw = tsent[t];
				for (int s = 0; s < ssent.length; s++) {
					int sw = ssent[s];
					s_total.put(tw, logAdd(s_total.getOrDefault(tw,
									Float.NEGATIVE_INFINITY), ttable
									.get(tw, sw, p)));
				}
			}

			// collect counts
			for (int t = 1; t < tsent.length; t++) {
				int tw = tsent[t];
				for (int s = 0; s < ssent.length; s++) {
					int sw = ssent[s];
					float in_count = sPD[sent] + (ttable.get(tw, sw, p) - s_total.get(tw));
					counts.put(
							tw,
							sw,
							logAdd(counts.get(tw, sw,
									Float.NEGATIVE_INFINITY), in_count));
					totals.put(
							sw,
							logAdd(totals.getOrDefault(sw,
									Float.NEGATIVE_INFINITY), in_count));
				}
			}
			
		}
	}
	
	
	public static void updateTranslationTable(final int src1[][],
			final int trg1[][], final int src2[][], final int trg2[][], final TranslationTable ttable, final float sPD1[], final float sPD2) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Updating translation table ... ");

				TranslationTable counts = new TranslationTable();
				HashIntFloatMap totals = HashIntFloatMaps.newMutableMap();

				// collect counts
				InvitationModel.collectCounts(src1, trg1, ttable, sPD1, counts, totals);
				float sPD2_temp[] = new float[src2.length];
				Arrays.fill(sPD2_temp, sPD2);
				InvitationModel.collectCounts(src2, trg2, ttable, sPD2_temp, counts, totals);

				// maximization
				for (int tw : counts.ttable.keySet()) {
					HashIntFloatMap tMap = counts.ttable.get(tw);
					for (int sw : tMap.keySet()) {
						float newProb = counts.get(tw, sw) - totals.get(sw);
						ttable.put(tw, sw, newProb);
					}
				}
				log.info("Updating translation table DONE");
				InvitationModel.latch.countDown();
			}

		});

	}	

	public static void readFiles() throws IOException, InterruptedException {

		log.info("Reading files");

		src_codes = HashObjIntMaps.newMutableMap();
		trg_codes = HashObjIntMaps.newMutableMap();
		src_codes.put(null, 0);
		trg_codes.put(null, 0);

		LineNumberReader lr = new LineNumberReader(new FileReader(IN + "."
				+ SRC));
		lr.skip(Long.MAX_VALUE);
		int indomain_size = lr.getLineNumber();
		lr.close();

		lr = new LineNumberReader(new FileReader(MIX + "." + SRC));
		lr.skip(Long.MAX_VALUE);
		int mixdomain_size = lr.getLineNumber();
		lr.close();

		src_indomain = new int[indomain_size][];
		trg_indomain = new int[indomain_size][];
		src_mixdomain = new int[mixdomain_size][];
		trg_mixdomain = new int[mixdomain_size][];

		latch = new CountDownLatch(2);
		readFile(IN + "." + SRC, src_codes, src_indomain);
		readFile(IN + "." + TRG, trg_codes, trg_indomain);
		latch.await();

		latch = new CountDownLatch(2);
		readFile(MIX + "." + SRC, src_codes, src_mixdomain);
		readFile(MIX + "." + TRG, trg_codes, trg_mixdomain);
		latch.await();
		
		for(int i=0;i<src_indomain.length;i++) {
			indomain_token_count += src_indomain[i].length;
		}

	}

	public static void readFile(final String fileName,
			final HashObjIntMap<String> codes, final int lines[][])
			throws IOException {
		jobs.execute(new Runnable() {
			@Override
			public void run() {
				try {
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(
									new FileInputStream(fileName), Charset
											.forName("UTF8")));
					String line = null;
					int i = 0;
					while ((line = reader.readLine()) != null) {
						String words[] = line.split("\\s+");
						lines[i] = new int[words.length + 1];
						lines[i][0] = 0;
						int j = 1;
						for (String word : words) {
							int code = 0;
							if (!codes.containsKey(word)) {
								code = codes.size();
								codes.put(word, code);
							} else {
								code = codes.getInt(word);
							}
							lines[i][j++] = code;
						}
						i++;
					}
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(1);
				}
				writeEncodedFile(fileName + ".encoded", lines);
				log.info(fileName + " ... DONE");
				InvitationModel.latch.countDown();
			}
		});
	}

	public static void writeEncodedFile(final String fileName,
			final int lines[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				try {
					BufferedWriter encodedWriter = new BufferedWriter(
							new OutputStreamWriter(new FileOutputStream(
									fileName), Charset.forName("UTF8")));
					for (int i = 0; i < lines.length; i++) {
						for (int j = 1; j < lines[i].length; j++) {
							int word = lines[i][j];
							encodedWriter.write("" + word);
							encodedWriter.write(" ");
						}
						encodedWriter.write("\n");
					}
					encodedWriter.close();
				} catch (IOException e) {
					e.printStackTrace();
				}

			}

		});

	}

	public static float getLMProb(NgramLanguageModel<String> lm, int sent[]) {
		List<String> words = new ArrayList<String>();
		for (int i = 1; i < sent.length; i++) {
			words.add("" + sent[i]);
		}
		return lm.getLogProb(words);
	}

	public static void createLM(final String fileName, final float lm[][],
			final int index, final int corpus[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Creating language model");

				NgramLanguageModel<String> createdLM = null;
				final int lmOrder = 4;
				final List<String> inputFiles = new ArrayList<String>();
				inputFiles.add(fileName);
				final StringWordIndexer wordIndexer = new StringWordIndexer();
				wordIndexer.setStartSymbol(ArpaLmReader.START_SYMBOL);
				wordIndexer.setEndSymbol(ArpaLmReader.END_SYMBOL);
				wordIndexer.setUnkSymbol(ArpaLmReader.UNK_SYMBOL);

				createdLM = LmReaders
						.readContextEncodedKneserNeyLmFromTextFile(inputFiles,
								wordIndexer, lmOrder, new ConfigOptions(),
								new File(fileName + ".lm"));

				lm[index] = new float[corpus.length];
				
				for (int i = 0; i < corpus.length; i++) {
					int sent[] = corpus[i];
					lm[index][i] = getLMProb(createdLM, sent);
				}

				log.info(".");

				InvitationModel.latch.countDown();
			}

		});
	}

	public static float logAdd(float a, float b) {
		float max, negDiff;
		if (a > b) {
			max = a;
			negDiff = b - a;
		} else {
			max = b;
			negDiff = a - b;
		}
		if (max == Float.NEGATIVE_INFINITY) {
			return max;
		} else if (negDiff < -20.0f) {
			return max;
		} else {
			return max + (float) Math.log(1.0 + Math.exp(negDiff));
		}
	}

}

class Result implements Comparable<Result> {

	int sentenceNumber;
	float score = 1;
	float lm_score = 1;

	public Result(int sentenceNumber, float score) {
		this.sentenceNumber = sentenceNumber + 1;
		this.score = score;
	}

	public Result(int sentenceNumber, float score, float lm_score) {
		this.sentenceNumber = sentenceNumber + 1;
		this.score = score;
		this.lm_score = lm_score;
	}

	@Override
	public int compareTo(Result result) {
		int cmp = Float.compare(result.score, this.score);
		if (cmp == 0) {
			cmp = Float.compare(result.lm_score, this.lm_score);
		}
		return cmp;
	}

}
