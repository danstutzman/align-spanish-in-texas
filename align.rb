require 'json'
require 'open3'

Dir.glob('*.vtt') do |vtt_path|
  utterances = File.read(vtt_path).split("\r\n\r\n")
  raise utterances.inspect if utterances[0] != "\ufeffWEBVTT"
  utterances.shift
  utterances.each do |utterance|
    utterance_num, timings, *lines = utterance.split("\r\n")
    # timings example: 00:00:01.250 --> 00:00:07.420
    match = timings.match(/^([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3}) --> ([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3})$/) or raise "Can't parse timings #{timings}"
    beginning_millis = match[1].to_i * 3600000 + \
                       match[2].to_i * 60000 + \
                       match[3].to_i * 1000 + \
                       match[4].to_i
    ending_millis =    match[5].to_i * 3600000 + \
                       match[6].to_i * 60000 + \
                       match[7].to_i * 1000 + \
                       match[8].to_i
    beginning_millis -= 1000
    beginning_millis = [beginning_millis, 0].max
    beginning = sprintf('%02d:%02d:%02d.%02d',
      beginning_millis / 3600000,
      (beginning_millis % 3600000) / 60000,
      (beginning_millis % 60000) / 1000,
      beginning_millis % 1000)
    ending = sprintf('%02d:%02d:%02d.%02d',
      ending_millis / 3600000,
      (ending_millis % 3600000) / 60000,
      (ending_millis % 60000) / 1000,
      ending_millis % 1000)

    wav_path = vtt_path.gsub(/\.vtt$/, '.wav')
    raise if wav_path == vtt_path
    command = ['sox', wav_path, '-r', '16000', 'excerpt.wav', 'trim',
      beginning, ending]
    puts command.join(' ')
    out, err, st = Open3.capture3(*command)

    File.open 'excerpt.txt', 'w' do |file|
      file.write lines.join(' ').downcase.gsub('>>i: ', '').gsub('>>s: ', '').gsub(/[,\.]( |$)/, ' ').gsub(/[¿?]/, '').gsub(/\.\.\./, ' ')
      file.write "\n"
    end

    command = ['java', '-cp',
      './sphinx4/sphinx4-samples/build/libs/sphinx4-samples-5prealpha-SNAPSHOT.jar',
      'edu.cmu.sphinx.demo.aligner.AlignerDemo',
      'excerpt.wav',
      'excerpt.txt',
      'voxforge-es-0.2/model_parameters/voxforge_es_sphinx.cd_ptm_3000',
      'voxforge-es-0.2/etc/voxforge_es_sphinx.dic']
    puts command.join(' ')
    out, err, st = Open3.capture3(*command)
    if out != ''
      puts out
    else
      raise err
    end

    alignments = []
    out.split("\n").each do |line|
      if line.start_with?('- ')
        alignments.push({
          path: vtt_path,
          type: 'missing',
          word: line[2..-1],
        })
      else
        match = line.match(/^([ +]) (.*?) +\[([0-9]+):([0-9]+)\]$/) \
          or raise "Can't parse line #{line}"
        alignments.push({
          path: vtt_path,
          type: (match[1] == '+') ? 'added' : 'present',
          word: match[2],
          begin_millis: match[3].to_i,
          end_millis: match[4].to_i,
        })
      end
    end
    File.open 'alignments.txt', 'a' do |file|
      file.write JSON.dump(alignments)
    end
  end
end
