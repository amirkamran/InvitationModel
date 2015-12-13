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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PerplexityCalculator {
	
	public static ExecutorService jobs = Executors.newFixedThreadPool(20);
	public static CountDownLatch latch = null;
		
	@SuppressWarnings("rawtypes")
	public static void main(String args[]) throws InterruptedException, UnsupportedEncodingException, FileNotFoundException {
		
		int files = Integer.parseInt(args[0]);
		String src = args[1];
		String trg = args[2];
		long tokens = Long.parseLong(args[3]);
		int splits = Integer.parseInt(args[4]);
	
		double [][]src_perp = null;
		double [][]trg_perp = null;

		src_perp = new double[files][splits];
		trg_perp = new double[files][splits];
		
		new File("./temp").mkdir();
		
		latch = new CountDownLatch(2*files*splits);
		for(int i=1;i<=files;i++) {
			String fileName = "selected" + i;
			Future f1 = splitFile(fileName, src, trg, tokens, splits);
			for(int j=1;j<=splits;j++) {
				Future f2 = runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -lm ./temp/" + fileName+"."+src+"."+j+".lm -text ./temp/" + fileName+"."+src+"."+j , f1);
				Future f3 = runCommand("./ngram -unk -lm ./temp/" + fileName+"."+src+"."+j +".lm -ppl ./test." + src + " > ./temp/" + fileName+"."+src+"."+j + ".ppl", f2);
				Future f4 = runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -lm ./temp/" + fileName+"."+trg+"."+j+".lm -text ./temp/" + fileName+"."+trg+"."+j , f1);
				Future f5 = runCommand("./ngram -unk -lm ./temp/" + fileName+"."+trg+"."+j +".lm -ppl ./test." + trg + " > ./temp/" + fileName+"."+trg+"."+j + ".ppl", f4);				
				readPpl(src_perp, "./temp/" + fileName+"."+src+"."+j + ".ppl", i-1, j-1, f3);
				readPpl(trg_perp, "./temp/" + fileName+"."+trg+"."+j + ".ppl", i-1, j-1, f5);
			}
		}
		latch.await();
		jobs.shutdown();
		PrintWriter ppl_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("ppl.txt"), "UTF8"));
		for(int i=0;i<files;i++) {
			for(int j=0;j<splits;j++) {
				ppl_out.print(Math.sqrt(src_perp[i][j]*trg_perp[i][j]) + "\t");
			}
			ppl_out.println();
		}
		ppl_out.close();
	}
	
	@SuppressWarnings("rawtypes")
	public static void readPpl(final double [][]perp, final String fileName, final int fileNumber, final int splitNumber, final Future future) {
		jobs.submit(new Runnable() {
			
			@Override
			public void run() {
				try{
					if(future!=null) future.get();
						
					BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName), "UTF8"));
					reader.readLine();
					String line = reader.readLine();						
					String value = line.split("\\s+")[5];
					perp[fileNumber][splitNumber] = Double.parseDouble(value);
					PerplexityCalculator.latch.countDown();
					reader.close();
					
				} catch(Exception e) {
					e.printStackTrace();
				}
				
			}
		});
	}
	
	@SuppressWarnings("rawtypes")
	public static Future splitFile(final String fileName, final String src, final String trg, final long tokens, final int splits) {
		return jobs.submit(new Runnable() {			
			@Override
			public void run() {
				try {
					
					System.out.println("Spliting file . . . " + fileName);
					
					long splitSize = tokens / splits;
					
					ArrayList<String> src_sentences = new ArrayList<String>();
					ArrayList<String> trg_sentences = new ArrayList<String>();
					
					BufferedReader src_reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName + "." + src), "UTF8"));
					BufferedReader trg_reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName + "." + trg), "UTF8"));
					String line = null;
					try{
						while((line=src_reader.readLine())!=null) {
							src_sentences.add(line);
							trg_sentences.add(trg_reader.readLine());
						}
						src_reader.close();
						trg_reader.close();
					}catch(Exception e){}
					
					
					int s = 0;
					int j = 1;
					
					PrintWriter src_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + src + "." + j), "UTF8"));
					PrintWriter trg_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + trg + "." + j), "UTF8"));
					
					try {
										
						for(int i=0;i<src_sentences.size();i++) {
							
							s += src_sentences.get(i).split("\\s+").length;
							src_out.println(src_sentences.get(i));
							trg_out.println(trg_sentences.get(i));
	
							if(s >= j*splitSize && j<splits) {
								i = 0;
								s = 0;
								src_out.close();
								trg_out.close();							
								j++;
								src_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + src + "." + j), "UTF8"));
								trg_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + trg + "." + j), "UTF8"));							
							}
							
						}
					
					}catch(Exception e) {
						src_out.close();
						trg_out.close();
					}
					
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}				
			}
		});
	}
	
	@SuppressWarnings("rawtypes")
	public static Future splitFile2(final String fileName, final String src, final String trg, final long tokens, final int splits) {
		return jobs.submit(new Runnable() {			
			@Override
			public void run() {
				try {
					
					System.out.println("Spliting file . . . " + fileName);
					
					long splitSize = tokens / splits;

					BufferedReader src_reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName + "." + src), "UTF8"));
					BufferedReader trg_reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName + "." + trg), "UTF8"));
					String line = null;
					int i = 1;
					int s = 1;
					PrintWriter src_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + src + "." + i), "UTF8"));
					PrintWriter trg_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + trg + "." + i), "UTF8"));
					try{
						while((line=src_reader.readLine())!=null) {
							s += line.split("\\s+").length;
							src_out.println(line);
							trg_out.println(trg_reader.readLine());
							if(s >= splitSize && i<splits) {
								s = 1;							
								src_out.close();
								trg_out.close();
								i++;
								src_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + src + "." + i), "UTF8"));
								trg_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + trg + "." + i), "UTF8"));
							}
						}
					}catch(Exception e){}
					src_out.close();
					trg_out.close();
					src_reader.close();
					trg_reader.close();
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(1);
				}				
			}
		});
	}	
	
	
    @SuppressWarnings("rawtypes")
	public static Future runCommand(final String command, final Future future) {
		return jobs.submit(new Runnable() {			
			@Override
			public void run() {
				try { 
					
					if(future!=null) {
						future.get();
					}
					
					System.out.println(command);
					
					String [] cmd = {"/bin/sh" , "-c", command};					
			        Process p = Runtime.getRuntime().exec(cmd);
			        p.waitFor();
			        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
			        String line = "";
			        while ((line = reader.readLine()) != null) {
			        	System.out.println(line);
			        }
			        reader.close();
			        reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			        while ((line = reader.readLine()) != null) {
			        	System.out.println(line);
			        }
			        reader.close();
			        
				} catch (Exception e) {
					System.out.println(e.getMessage());
					System.exit(1);
				}				
			}
		});
    }
    
    @SuppressWarnings("rawtypes")
    public static Future runCommand(String command) {
    	return runCommand(command, null);
    }
	
	
}