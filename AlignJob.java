import edu.cmu.sphinx.alignment.LongTextAligner;
import edu.cmu.sphinx.api.SpeechAligner;
import edu.cmu.sphinx.result.WordResult;
import java.util.concurrent.Callable;
import java.lang.ProcessBuilder;
import java.lang.Process;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AlignJob implements Callable<Integer> {
  public String wavPath;
  public int utteranceNum;
  public int beginMillis;
  public int endMillis;
  public String transcription;

  public Integer call() {
    String beginTiming = String.format("%02d:%02d:%02d.%03d",
      this.beginMillis / 3600000,
      (this.beginMillis % 3600000) / 60000,
      (this.beginMillis % 60000) / 1000,
      this.beginMillis % 1000);
    String endTiming = String.format("%02d:%02d:%02d.%03d",
      this.endMillis / 3600000,
      (this.endMillis % 3600000) / 60000,
      (this.endMillis % 60000) / 1000,
      this.endMillis % 1000);

    String excerptWavPath = "" + utteranceNum + ".wav";
    StringBuilder soxOutput = new StringBuilder();
    Process process;
    try {
      process = new ProcessBuilder().inheritIO().command(
        "/usr/bin/sox", this.wavPath, "-r", "16000", excerptWavPath, "trim",
        beginTiming, endTiming).start();
      BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()));
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          break;
        }
        soxOutput.append(line);
        soxOutput.append("\n");
      }
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

    int exitValue;
    try {
      exitValue = process.waitFor();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (exitValue != 0) {
      throw new RuntimeException("Got non-zero status of " + exitValue + 
        "from sox, output = " + soxOutput);
    }

    try {
      String excerptTxtPath = "" + utteranceNum + ".txt";
      FileWriter writer = new FileWriter(excerptTxtPath);
      writer.write(this.transcription);
      writer.write("\n");
      writer.close();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

    
    try {
      URL audioUrl = new File(excerptWavPath).toURI().toURL();
      String acousticModelPath =
        "voxforge-es-0.2/model_parameters/voxforge_es_sphinx.cd_ptm_3000";
      String dictionaryPath = "voxforge-es-0.2/etc/voxforge_es_sphinx.dic";
      String g2pPath = null;
      SpeechAligner aligner =
        new SpeechAligner(acousticModelPath, dictionaryPath, g2pPath);

      List<WordResult> results = aligner.align(audioUrl, this.transcription);
      List<String> stringResults = new ArrayList<String>();
      for (WordResult wr : results) {
        stringResults.add(wr.getWord().getSpelling());
      }

      LongTextAligner textAligner = new LongTextAligner(stringResults, 2);
      List<String> sentences = aligner.getTokenizer().expand(this.transcription);
      List<String> words = aligner.sentenceToWords(sentences);
      int[] aid = textAligner.align(words);

      int lastId = -1;
      for (int i = 0; i < aid.length; ++i) {
        if (aid[i] == -1) {
          System.out.format("- %s\n", words.get(i));
        } else {
          if (aid[i] - lastId > 1) {
            for (WordResult result : results.subList(lastId + 1, aid[i])) {
              System.out.format("+ %-25s [%s]\n", result.getWord()
                .getSpelling(), result.getTimeFrame());
            }
          }
          System.out.format("  %-25s [%s]\n", results.get(aid[i])
              .getWord().getSpelling(), results.get(aid[i])
              .getTimeFrame());
          lastId = aid[i];
        }
      }

      if (lastId >= 0 && results.size() - lastId > 1) {
        for (WordResult result : results.subList(lastId + 1,
            results.size())) {
          System.out.format("+ %-25s [%s]\n", result.getWord()
            .getSpelling(), result.getTimeFrame());
        }
      }

    } catch (java.net.MalformedURLException e) {
      throw new RuntimeException(e);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }

/*
      './sphinx4/sphinx4-samples/build/libs/sphinx4-samples-5prealpha-SNAPSHOT.jar',
      'edu.cmu.sphinx.demo.aligner.AlignerDemo',
      'excerpt.wav',
      'excerpt.txt',
      'voxforge-es-0.2/etc/voxforge_es_sphinx.dic']
*/

    return 0;
  }
}
