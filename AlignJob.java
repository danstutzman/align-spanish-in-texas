import edu.cmu.sphinx.alignment.LongTextAligner;
import edu.cmu.sphinx.api.SpeechAligner;
import edu.cmu.sphinx.result.WordResult;
import edu.cmu.sphinx.util.TimeFrame;
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

public class AlignJob implements Callable<List<WordResult>> {
  public String wavPath;
  public int utteranceNum;
  public int beginMillis;
  public int endMillis;
  public String transcription;

  public List<WordResult> call() {
    String beginTiming = String.format("%02d:%02d:%02d.%03d",
      this.beginMillis / 3600000,
      (this.beginMillis % 3600000) / 60000,
      (this.beginMillis % 60000) / 1000,
      this.beginMillis % 1000);
    int durationMillis = this.endMillis - this.beginMillis;
    String durationTiming = String.format("%02d:%02d:%02d.%03d",
      durationMillis / 3600000,
      (durationMillis % 3600000) / 60000,
      (durationMillis % 60000) / 1000,
      durationMillis % 1000);

    new File("tmp").mkdir();

    String excerptWavPath = "tmp/" + utteranceNum + ".wav";
    StringBuilder soxOutput = new StringBuilder();
    Process process;
    try {
      process = new ProcessBuilder().inheritIO().command(
        "/usr/bin/sox", this.wavPath, "-r", "16000", excerptWavPath, "trim",
        beginTiming, durationTiming).start();
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
      String excerptTxtPath = "tmp/" + utteranceNum + ".txt";
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

      List<WordResult> resultsRelative = aligner.align(audioUrl, this.transcription);

      List<WordResult> results = new ArrayList<WordResult>();
      for (WordResult wordRelative : resultsRelative) {
        TimeFrame newTimeFrame = new TimeFrame(
          wordRelative.getTimeFrame().getStart() + this.beginMillis,
          wordRelative.getTimeFrame().getEnd()   + this.beginMillis);
        results.add(new WordResult(
          wordRelative.getWord(),
          newTimeFrame,
          wordRelative.getScore(),
          wordRelative.getConfidence()));
      }

      return results;
    } catch (java.net.MalformedURLException e) {
      throw new RuntimeException(e);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }
}
