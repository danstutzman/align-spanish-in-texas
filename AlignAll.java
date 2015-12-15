import edu.cmu.sphinx.result.WordResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONWriter;

public class AlignAll {
  private static String readFile(File file) {
    StringBuilder contents = new StringBuilder();
    try {
      BufferedReader reader =
        new BufferedReader(new InputStreamReader(new FileInputStream(file)));
      String line = reader.readLine();
      if (!line.equals("\ufeffWEBVTT")) {
        throw new RuntimeException("Unexpected first line: " + line);
      }
      line = reader.readLine();
      if (!line.equals("")) {
        throw new RuntimeException("Unexpected second line: " + line);
      }

      while (true) {
        line = reader.readLine();
        if (line == null) {
          break;
        }
        contents.append(line);
        contents.append("\n");
      }
      reader.close();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    return contents.toString();
  }

  public static void main(String[] argv) {
    CommandLineParser parser = new DefaultParser();
    Options options = new Options();
    options.addOption("num_threads", true, "num of concurrent worker threads");
    CommandLine line;
    try {
      line = parser.parse(options, argv);
    } catch (org.apache.commons.cli.ParseException e) {
      throw new RuntimeException(e);
    }

    int numThreads;
    if (line.getOptionValue("num_threads") == null) {
      throw new RuntimeException("Missing option -num_threads");
    }
    numThreads = Integer.parseInt(line.getOptionValue("num_threads"));

    List<AlignJob> jobs = new ArrayList<AlignJob>();
    for (String vttPath : line.getArgs()) {
      String vtt = readFile(new File(vttPath));

      Pattern timingsPattern = Pattern.compile("([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3}) --> ([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})");
      for (String utterance : vtt.split("\n\n")) {
        String[] lines = utterance.split("\n");
        int utteranceNum = Integer.parseInt(lines[0]);
//if (utteranceNum > 1) break;

        String timings = lines[1];
        Matcher matcher = timingsPattern.matcher(timings);
        if (!matcher.find()) {
          throw new RuntimeException("Couldn't parse timings line: " + timings);
        }
        int beginMillis = Integer.parseInt(matcher.group(1)) * 3600 * 1000 +
                          Integer.parseInt(matcher.group(2)) *   60 * 1000 +
                          Integer.parseInt(matcher.group(3))        * 1000 +
                          Integer.parseInt(matcher.group(4));
        int endMillis =   Integer.parseInt(matcher.group(5)) * 3600 * 1000 +
                          Integer.parseInt(matcher.group(6)) *   60 * 1000 +
                          Integer.parseInt(matcher.group(7))        * 1000 +
                          Integer.parseInt(matcher.group(8));
        StringBuilder transcription = new StringBuilder();
        for (int i = 2; i < lines.length; i++) {
          transcription.append(lines[i]
            .replace("¿", " ")
            .replace(">>i: ", "")
            .replace(">>s: ", "")
          );
          transcription.append(" ");
        }
        AlignJob job = new AlignJob();
        job.wavPath       = vttPath.replace(".vtt", ".wav");
        job.utteranceNum  = utteranceNum;
        job.beginMillis   = beginMillis;
        job.endMillis     = endMillis;
        job.transcription = transcription.toString();
        jobs.add(job);
      }
    }

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    try {
      List<Future<List<WordResult>>> futures =
        new ArrayList<Future<List<WordResult>>>();
      for (AlignJob job : jobs) {
        //System.out.println("submitting...");
        Future<List<WordResult>> thisFuture = executor.submit(job);
        futures.add(thisFuture);
/*
        for (Iterator<Future<List<WordResult>>> it = futures.listIterator();
            it.hasNext(); ) {
          Future<List<WordResult>> future = it.next();
          if (future.isDone()) {
            try {
              System.out.println("result: " + future.get());
            } catch (java.lang.InterruptedException e) {
              throw new RuntimeException(e);
            } catch (java.util.concurrent.ExecutionException e) {
              throw new RuntimeException(e);
            }
            it.remove();
          }
        }*/
      }

      boolean allFinished = false;
      while (!allFinished) {
        allFinished = true;
        for (Iterator<Future<List<WordResult>>> it = futures.listIterator();
            it.hasNext(); ) {
          Future<List<WordResult>> future = it.next();
          if (future.isDone()) {
            List<WordResult> words;
            try {
              words = future.get();
            } catch (java.lang.InterruptedException e) {
              throw new RuntimeException(e);
            } catch (java.util.concurrent.ExecutionException e) {
              throw new RuntimeException(e);
            }
            it.remove();

            try {
              BufferedWriter writer =
                new BufferedWriter(new FileWriter("align.out", true));
              JSONWriter jsonWriter = new JSONWriter(writer);
              jsonWriter.array();
              for (WordResult word : words) {
                jsonWriter
                  .array()
                  .value(word.getWord().getSpelling())
                  .value(word.getTimeFrame().getStart())
                  .value(word.getTimeFrame().getEnd())
                  .endArray();
              }
              jsonWriter.endArray();
              writer.write("\n");
              writer.close();
            } catch (java.io.IOException e) {
              throw new RuntimeException(e);
            }

          } else {
            allFinished = false;
          }
        }
      }

      try {
        System.err.println("attempt to shutdown executor");
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        System.err.println("tasks interrupted");
      }
      finally {
        if (!executor.isTerminated()) {
          System.err.println("cancel non-finished tasks");
        }
        executor.shutdownNow();
        System.err.println("shutdown finished");
      }
    } catch (RuntimeException e) {
      try {
        Thread.sleep(4000);
      } catch (java.lang.InterruptedException e2) { }

      e.printStackTrace();
      System.exit(1);
    }
  }
}
