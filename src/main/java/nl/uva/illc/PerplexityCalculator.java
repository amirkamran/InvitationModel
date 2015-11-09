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
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PerplexityCalculator {
	
	public static ExecutorService jobs = Executors.newFixedThreadPool(20);
	public static CountDownLatch latch = null;
	
	public static double [][]perp = null;
	
	@SuppressWarnings("rawtypes")
	public static void main(String args[]) throws InterruptedException {
		
		int files = Integer.parseInt(args[0]);
		String lang = args[1];
		
		perp = new double[files][19];
		
		new File("./temp").mkdir();
		
		latch = new CountDownLatch(files*perp[0].length);
		for(int i=1;i<=perp.length;i++) {
			String fileName = "selected" + i + "." + lang;
			Future f1 = splitFile(fileName, 100000);
			for(int j=1;j<=perp[i-1].length;j++) {
				Future f2 = runCommand("./ngram-count -order 5 -interpolate -kndiscount3 -kndiscount5 -lm ./temp/" + fileName+"."+j+".lm -text ./temp/" + fileName+"."+j , f1);
				Future f3 = runCommand("./ngram -lm ./temp/" + fileName+"."+j +".lm -ppl ./test." + lang + " > ./temp/" + fileName+"."+j + ".ppl", f2);
				readPpl("./temp/" + fileName+"."+j + ".ppl", i-1, j-1, f3);
			}
		}
		latch.await();
		jobs.shutdown();
		for(int i=0;i<perp.length;i++) {
			for(int j=0;j<perp[i].length;j++) {
				System.out.print(perp[i][j] + "\t");
			}
			System.out.println();
		}
	}
	
	@SuppressWarnings("rawtypes")
	public static void readPpl(final String fileName, final int fileNumber, final int splitNumber, final Future future) {
		jobs.submit(new Runnable() {
			
			@Override
			public void run() {
				try{
					if(future!=null) future.get();
						
					BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName), "UTF8"));
					reader.readLine();
					String line = reader.readLine();						
					String value = line.substring(line.indexOf("ppl= ")+5, line.indexOf(" ppl1")-1);
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
	public static Future splitFile(final String fileName, final int splitSize) {
		return jobs.submit(new Runnable() {			
			@Override
			public void run() {
				try {
					
					System.out.println("Spliting file . . . " + fileName);
					
					BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName), "UTF8"));					
					String line = null;
					int i = 1;
					int s = 1;
					PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + i), "UTF8"));			
					while((line=reader.readLine())!=null) {
						out.println(line);
						if(++s >= splitSize) {
							s = 1;
							out.close();
							out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("./temp/" + fileName + "." + ++i), "UTF8"));
						}
					}
					out.close();
					reader.close();
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
