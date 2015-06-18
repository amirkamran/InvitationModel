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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.openhft.koloboke.collect.map.hash.HashIntDoubleMap;
import net.openhft.koloboke.collect.map.hash.HashIntDoubleMaps;
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
* Invitation based data selection approach exploits in-domain data
* (both monolingual and bilingual) as prior to guide word alignment
* and phrase pair estimates in the large mix-domain corpus. As a 
* by-product, accurate estimates for P(D|e,f) of the mixed-domain 
* sentences are produced (with D being either in-domain or out-of-domain),
* which can be used to rank the sentences in Dmix according to their
* relevance to Din.
* 
* For more information see:
* Hoang, Cuong and Sima'an, Khalil (2014): Latent Domain Translation Models
* in Mix-of-Domains Haystack, Proceedings of COLING 2014, the 25th 
* International Conference on Computational Linguistics
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

	static HashObjIntMap<String> src_codes = null;
	static HashObjIntMap<String> trg_codes = null;

	static NgramLanguageModel<String> lm[] = null;

	static double PD1 = 0.5;
	static double PD0 = 0.5;

	static TranslationTable ttable[] = new TranslationTable[4];

	public static CountDownLatch latch = null;
	public static ExecutorService jobs = Executors.newCachedThreadPool();

	public static HashIntIntMap ignore = HashIntIntMaps.newMutableMap();

	public static double n = 0.5d;
	public static double V = 100000d;
	public static double p = 1d / V;

	public static void main(String args[]) throws IOException,
			InterruptedException {
		log.info("Start ...");
		processCommandLineArguments(args);
		readFiles();
		initialize();
		burnIN();
		createLM();
		training();
		log.info("END");
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

		CommandLineParser parser = new GnuParser();
		try {
			CommandLine cmd = parser.parse(options, args);
			if (cmd.hasOption("cmix") && cmd.hasOption("cin")
					&& cmd.hasOption("src") && cmd.hasOption("trg")) {
				MIX = cmd.getOptionValue("cmix");
				IN = cmd.getOptionValue("cin");
				SRC = cmd.getOptionValue("src");
				TRG = cmd.getOptionValue("trg");
				
				if(cmd.hasOption("i")) {
					iMAX = Integer.parseInt(cmd.getOptionValue("i"));
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

		latch = new CountDownLatch(4);

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

				HashIntDoubleMap totals = HashIntDoubleMaps.newMutableMap();

				for (int sent = 0; sent < src.length; sent++) {

					if (sent % 100000 == 0)
						log.debug("Sentence " + sent);

					int ssent[] = src[sent];
					int tsent[] = trg[sent];
					for (int t = 1; t < tsent.length; t++) {
						int tw = tsent[t];
						for (int s = 0; s < ssent.length; s++) {
							int sw = ssent[s];
							ttable.increas(tw, sw, 1d);
							totals.addValue(sw, 1d, 1d);
						}
					}
				}

				// normalizing and smoothing
				for (int tw : ttable.ttable.keySet()) {
					HashIntDoubleMap tMap = ttable.ttable.get(tw);
					for (int sw : tMap.keySet()) {
						double prob = (ttable.get(tw, sw) + n)
								/ (totals.get(sw) + n * V);
						ttable.put(tw, sw, prob);
					}
				}

				log.info(".");

				InvitationModel.latch.countDown();
			}

		});

	}

	@SuppressWarnings("unchecked")
	public static void createLM() throws InterruptedException {

		log.info("Creating Language Models ...");

		lm = new NgramLanguageModel[4];

		latch = new CountDownLatch(4);

		createLM(IN + "." + SRC + ".encoded", lm, 0);
		createLM(IN + "." + TRG + ".encoded", lm, 1);
		createLM("outdomain." + SRC + ".encoded", lm, 2);
		createLM("outdomain." + TRG + ".encoded", lm, 3);

		latch.await();

		log.info("DONE");

	}

	public static void burnIN() throws IOException, InterruptedException {

		log.info("BurnIN started ... ");

		HashIntObjMap<Result> results = null;

		for (int i = 1; i <= 1; i++) {

			log.info("Iteration " + i);

			results = HashIntObjMaps.newMutableMap();

			double sPD[][] = new double[2][src_mixdomain.length];

			int split = (int) Math.ceil(src_mixdomain.length / 100000d);

			latch = new CountDownLatch(split);
			for (int sent = 0; sent < src_mixdomain.length; sent += 100000) {
				int end = sent + 100000;
				if (end > src_mixdomain.length) {
					end = src_mixdomain.length;
				}
				calcualteBurnInScore(sent, end, sPD);
			}
			latch.await();

			double countPD[] = new double[2];

			for (int sent = 0; sent < src_mixdomain.length; sent++) {

				if (ignore.containsKey(sent))
					continue;

				if (Double.isNaN(sPD[0][sent]) || Double.isNaN(sPD[1][sent])) {
					ignore.put(sent, sent);
					log.info("Ignoring " + (sent + 1));
					continue;
				}

				countPD[0] += sPD[0][sent];
				countPD[1] += sPD[1][sent];

				results.put(sent, new Result(sent, sPD[0][sent]));

			}

		}

		log.info("BurnIN DONE");

		log.info("Writing outdomain corpus ... ");

		ArrayList<Result> sortedResult = new ArrayList<Result>(results.values());
		Collections.sort(sortedResult);

		PrintWriter src_out = new PrintWriter("outdomain." + SRC + ".encoded");
		PrintWriter trg_out = new PrintWriter("outdomain." + TRG + ".encoded");

		PrintWriter out_score = new PrintWriter("outdomain.scores");

		src_outdomain = new int[src_indomain.length][];
		trg_outdomain = new int[trg_indomain.length][];

		int j = 0;

		for (Result r : sortedResult) {

			int ssent[] = src_mixdomain[r.sentenceNumber];
			int tsent[] = trg_mixdomain[r.sentenceNumber];

			out_score.println(r.sentenceNumber + "\t" + r.score);

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

			j++;

			if (j == src_indomain.length) {
				break;
			}
		}

		out_score.close();

		src_out.close();
		trg_out.close();

		log.info("DONE");

	}

	public static void training() throws FileNotFoundException,
			InterruptedException {

		log.info("Starting Invitation EM ...");

		latch = new CountDownLatch(2);
		initializeTranslationTable(src_outdomain, trg_outdomain, ttable[2]);
		initializeTranslationTable(trg_outdomain, src_outdomain, ttable[3]);
		latch.await();

		for (int i = 1; i <= iMAX; i++) {
			log.info("Iteration " + i);
			HashIntObjMap<Result> results = HashIntObjMaps.newMutableMap();

			double sPD[][] = new double[2][src_mixdomain.length];

			int split = (int) Math.ceil(src_mixdomain.length / 100000d);

			latch = new CountDownLatch(split);
			for (int sent = 0; sent < src_mixdomain.length; sent += 100000) {
				int end = sent + 100000;
				if (end > src_mixdomain.length) {
					end = src_mixdomain.length;
				}
				calcualteScore(sent, end, sPD);
			}
			latch.await();

			double countPD[] = new double[2];

			for (int sent = 0; sent < src_mixdomain.length; sent++) {

				if (ignore.containsKey(sent))
					continue;

				if (Double.isNaN(sPD[0][sent]) || Double.isNaN(sPD[1][sent])) {
					ignore.put(sent, sent);
					log.info("Ignoring " + (sent + 1));
					continue;
				}

				countPD[0] += sPD[0][sent];
				countPD[1] += sPD[1][sent];

				double srcP = getLMProb(lm[0], src_mixdomain[sent]);
				double trgP = getLMProb(lm[1], trg_mixdomain[sent]);
				results.put(sent, new Result(sent, sPD[1][sent], srcP * trgP));

			}

			writeResult(i, results);

			latch = new CountDownLatch(4);
			updateTranslationTable(src_mixdomain, trg_mixdomain, ttable[0], sPD[1]);
			updateTranslationTable(trg_mixdomain, src_mixdomain, ttable[1], sPD[1]);
			updateTranslationTable(src_mixdomain, trg_mixdomain, ttable[2], sPD[0]);
			updateTranslationTable(trg_mixdomain, src_mixdomain, ttable[3], sPD[0]);
			latch.await();

			PD1 = countPD[1] / (countPD[0] + countPD[1]);
			PD0 = 1 - PD1;

			// AlignmentCalculator.process(src_mixdomain, trg_mixdomain,
			// ttable[0], ttable[1]);

			log.info("PD1 ~ PD0 " + PD1 + " ~ " + PD0);
		}
	}

	public static void calcualteScore(final int start, final int end,
			final double sPD[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				for (int sent = start; sent < end; sent++) {

					if (ignore.containsKey(sent))
						continue;

					int ssent[] = src_mixdomain[sent];
					int tsent[] = trg_mixdomain[sent];

					double sProb[] = new double[4];

					sProb[0] = calculateProb(ssent, tsent, ttable[0]);
					sProb[1] = calculateProb(tsent, ssent, ttable[1]);
					sProb[2] = calculateProb(ssent, tsent, ttable[2]);
					sProb[3] = calculateProb(tsent, ssent, ttable[3]);

					double in_score = PD1
							* (sProb[0] * getLMProb(lm[1], tsent) + sProb[1]
									* getLMProb(lm[0], ssent));
					double mix_score = PD0
							* (sProb[2] * getLMProb(lm[3], tsent) + sProb[3]
									* getLMProb(lm[2], ssent));

					sPD[1][sent] = in_score / (in_score + mix_score);
					sPD[0][sent] = 1 - sPD[1][sent];

				}
				InvitationModel.latch.countDown();
			}
		});

	}

	public static void calcualteBurnInScore(final int start, final int end,
			final double sPD[][]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				for (int sent = start; sent < end; sent++) {

					if (ignore.containsKey(sent))
						continue;

					int ssent[] = src_mixdomain[sent];
					int tsent[] = trg_mixdomain[sent];

					double sProb[] = new double[4];

					sProb[0] = calculateProb(ssent, tsent, ttable[0]);
					sProb[1] = calculateProb(tsent, ssent, ttable[1]);
					sProb[2] = calculateProb(ssent, tsent, ttable[2]);
					sProb[3] = calculateProb(tsent, ssent, ttable[3]);

					double in_score = PD1 * (sProb[0] + sProb[1]);
					double mix_score = PD0 * (sProb[2] + sProb[3]);

					sPD[1][sent] = in_score / (in_score + mix_score);
					sPD[0][sent] = 1 - sPD[1][sent];

				}
				InvitationModel.latch.countDown();
			}
		});

	}

	public static void writeResult(final int iterationNumber,
			final HashIntObjMap<Result> results) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				ArrayList<Result> sortedResult = new ArrayList<Result>(results
						.values());
				Collections.sort(sortedResult);
				try {
					PrintWriter output = new PrintWriter("output_"
							+ iterationNumber + ".txt");
					for (Result r : sortedResult) {
						output.println(r.sentenceNumber + "\t" + r.score + "\t"
								+ r.lm_score);
					}
					output.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		});

	}

	public static double calculateProb(final int ssent[], final int tsent[],
			final TranslationTable ttable) {
		double prob = 1d;
		for (int t = 1; t < tsent.length; t++) {
			int tw = tsent[t];
			double sum = 0;
			for (int s = 0; s < ssent.length; s++) {
				int sw = ssent[s];
				sum += ttable.get(tw, sw, p);
			}
			prob *= sum;
		}
		return prob;
	}

	public static void updateTranslationTable(final int src[][],
			final int trg[][], final TranslationTable ttable,
			final double sPD[]) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Updating translation table ... ");

				TranslationTable counts = new TranslationTable();
				HashIntDoubleMap totals = HashIntDoubleMaps.newMutableMap();

				for (int sent = 0; sent < src.length; sent++) {

					if (sent % 100000 == 0)
						log.debug("Sentence " + sent);

					if (ignore.containsKey(sent))
						continue;

					int ssent[] = src[sent];
					int tsent[] = trg[sent];

					HashIntDoubleMap s_total = HashIntDoubleMaps
							.newMutableMap();

					// calculating normalization
					for (int t = 1; t < tsent.length; t++) {
						int tw = tsent[t];
						s_total.put(tw, 0);
						for (int s = 0; s < ssent.length; s++) {
							int sw = ssent[s];
							s_total.addValue(tw, ttable.get(tw, sw, p),
									ttable.get(tw, sw, p));
						}
					}

					// collect counts
					for (int t = 1; t < tsent.length; t++) {
						int tw = tsent[t];
						for (int s = 0; s < ssent.length; s++) {
							int sw = ssent[s];
							double in_count = sPD[sent]
									* (ttable.get(tw, sw, p) / s_total.get(tw));
							counts.increas(tw, sw, in_count);
							totals.addValue(sw, in_count, in_count);
						}
					}
				}

				// maximization
				for (int tw : counts.ttable.keySet()) {
					HashIntDoubleMap tMap = counts.ttable.get(tw);
					for (int sw : tMap.keySet()) {
						double newProb = counts.get(tw, sw) / totals.get(sw);
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
								code = codes.size() + 1;
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

	public static double getLMProb(NgramLanguageModel<String> lm, int sent[]) {
		List<String> words = new ArrayList<String>();
		for (int i = 1; i < sent.length; i++) {
			words.add("" + sent[i]);
		}
		return Math.pow(10, lm.getLogProb(words));
	}

	public static void createLM(final String fileName,
			final NgramLanguageModel<String> lm[], final int index) {

		jobs.execute(new Runnable() {

			@Override
			public void run() {
				log.info("Creating language model");

				final int lmOrder = 4;
				final List<String> inputFiles = new ArrayList<String>();
				inputFiles.add(fileName);
				final StringWordIndexer wordIndexer = new StringWordIndexer();
				wordIndexer.setStartSymbol(ArpaLmReader.START_SYMBOL);
				wordIndexer.setEndSymbol(ArpaLmReader.END_SYMBOL);
				wordIndexer.setUnkSymbol(ArpaLmReader.UNK_SYMBOL);

				lm[index] = LmReaders
						.readContextEncodedKneserNeyLmFromTextFile(inputFiles,
								wordIndexer, lmOrder, new ConfigOptions(),
								new File(fileName + ".lm"));

				log.info(".");

				InvitationModel.latch.countDown();
			}

		});
	}

}

class Result implements Comparable<Result> {

	int sentenceNumber;
	double score = 1;
	double lm_score = 1;

	public Result(int sentenceNumber, double score) {
		this.sentenceNumber = sentenceNumber + 1;
		this.score = score;
	}

	public Result(int sentenceNumber, double score, double lm_score) {
		this.sentenceNumber = sentenceNumber + 1;
		this.score = score;
		this.lm_score = lm_score;
	}

	@Override
	public int compareTo(Result result) {
		int cmp = Double.compare(result.score, this.score);
		if (cmp == 0) {
			cmp = Double.compare(result.lm_score, this.lm_score);
		}
		return cmp;
	}

}