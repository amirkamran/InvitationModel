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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PerplexityCalculator {
	
	public static CountDownLatch latch = null;
	public static ExecutorService jobs = Executors.newCachedThreadPool();
		
	@SuppressWarnings("rawtypes")
	public static void main(String args[]) throws InterruptedException, UnsupportedEncodingException, FileNotFoundException {
		
		int files1 = Integer.parseInt(args[0]);
		int files2 = Integer.parseInt(args[1]);
		String src = args[2];
		String trg = args[3];
		long tokens = Long.parseLong(args[4]);
		int splits = Integer.parseInt(args[5]);
		int upto = Integer.parseInt(args[6]);
	
		double [][]src_perp = null;
		double [][]trg_perp = null;

		int d = files2 - files1 + 1;
		
		src_perp = new double[d][upto];
		trg_perp = new double[d][upto];
		
		new File("./temp").mkdir();
		
		runCommand("./ngram-count -text cmix." + src + " -write-order 1 -write-vocab ./temp/cmix." + src + ".vocab");
		runCommand("./ngram-count -text cmix." + trg + " -write-order 1 -write-vocab ./temp/cmix." + trg + ".vocab");
		
		for(int i=0;i<d;i++) {
						
			String fileName = "selected" + (i+files1);
			Future sf = splitFile(fileName, src, trg, tokens, splits, upto);
		
			latch = new CountDownLatch(upto);			
			for(int j=1;j<=upto;j++) {
				runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -vocab ./temp/cmix." +src+ ".vocab -write ./temp/" + fileName+"."+src+"."+j + ".count -text ./temp/" + fileName+"."+src+"."+j, sf);
			}			
			latch.await();
			
			latch = new CountDownLatch(upto);			
			for(int j=1;j<=upto;j++) {
				runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -vocab ./temp/cmix." +trg+ ".vocab -write ./temp/" + fileName+"."+trg+"."+j + ".count -text ./temp/" + fileName+"."+trg+"."+j, sf);				
			}			
			latch.await();
			
			
			runCommand("cp ./temp/"+ fileName+"."+src+".1.count ./temp/" + fileName+"."+src+".count");
			runCommand("cp ./temp/"+ fileName+"."+trg+".1.count ./temp/" + fileName+"."+trg+".count");
			
			for(int j=1;j<=upto;j++) {

				runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -vocab ./temp/cmix." +src+ ".vocab -lm ./temp/" + fileName+"."+src+".lm -read ./temp/" + fileName+"."+src+".count");
				runCommand("./ngram-count -unk -interpolate -order 5 -kndiscount -vocab ./temp/cmix." +trg+ ".vocab -lm ./temp/" + fileName+"."+trg+".lm -read ./temp/" + fileName+"."+trg+".count");

				runCommand("./ngram -unk -lm ./temp/" + fileName+"."+src+".lm -ppl ./test." + src + " > ./temp/" + fileName+"."+src+"."+j + ".ppl");
				runCommand("./ngram -unk -lm ./temp/" + fileName+"."+src+".lm -ppl ./test." + src + " > ./temp/" + fileName+"."+src+"."+j + ".ppl");
				
				readPpl(src_perp, "./temp/" + fileName+"."+src+"."+j + ".ppl", i, j-1);
				readPpl(trg_perp, "./temp/" + fileName+"."+trg+"."+j + ".ppl", i, j-1);

				if(j<upto) {
					runCommand("./ngram-merge -write ./temp/" + fileName+"."+src+".count.tmp ./temp/" + fileName+"."+src+".count ./temp/" + fileName+"."+src+"."+(j+1)+".count");
					runCommand("./ngram-merge -write ./temp/" + fileName+"."+trg+".count.tmp ./temp/" + fileName+"."+trg+".count ./temp/" + fileName+"."+trg+"."+(j+1)+".count");
					runCommand("mv ./temp/"+ fileName+"."+src+".count.tmp ./temp/" + fileName+"."+src+".count");
					runCommand("mv ./temp/"+ fileName+"."+trg+".count.tmp ./temp/" + fileName+"."+trg+".count");					
				}
				
			}
						
		}
		PrintWriter ppl_out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("ppl.txt"), "UTF8"));
		for(int i=0;i<d;i++) {
			for(int j=0;j<upto;j++) {
				ppl_out.print(Math.sqrt(src_perp[i][j]*trg_perp[i][j]) + "\t");
			}
			ppl_out.println();
		}
		ppl_out.close();
	}
	
	public static void readPpl(final double [][]perp, final String fileName, final int fileNumber, final int splitNumber) {
		try{
			BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName), "UTF8"));
			reader.readLine();
			String line = reader.readLine();						
			String value = line.split("\\s+")[5];
			perp[fileNumber][splitNumber] = Double.parseDouble(value);
			reader.close();				
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("rawtypes")
	public static Future splitFile(final String fileName, final String src, final String trg, final long tokens, final int splits, final int upto) {
		
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
								if(i==upto+1) break;
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
	public static Future runCommand(final String command, final Future dependent) {
		return jobs.submit(new Runnable() {

			@Override
			public void run() {
				try { 			
					System.out.println(command);
					
					if(dependent != null) {
						dependent.get();
					}
					
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
				PerplexityCalculator.latch.countDown();
			}
			
		});
    }
    
    @SuppressWarnings("rawtypes")
	public static void runCommand(String command) {
    	try {
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
	
}