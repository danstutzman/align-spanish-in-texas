require 'json'
require 'sinatra'

$words = []
File.open('align.out').each_line do |line|
  $words += JSON.parse(line)
end

get '/excerpt.wav' do
  begin_millis = params['begin_millis'].to_i
  end_millis   = params['end_millis'].to_i
  `sox AM001_1925_EP_SU2011_AD.wav excerpt.wav trim #{begin_millis / 1000.0} #{(end_millis - begin_millis) / 1000.0}`
  send_file 'excerpt.wav', :type => :wav
end

get '/' do
  html = ''
  html += "<html>\n"
  html += %q[<script>
    function play(begin_millis, end_millis) {
      var myAudio = new Audio("excerpt.wav?begin_millis=" + begin_millis +
        "&end_millis=" + end_millis);
      myAudio.play();
    }
    </script>]
  $words.each_with_index do |word, word_num|
    html += "<a href='##{word[1]}' onClick='play(#{word[1]}, #{word[2]}); return false;'>"
    html += word[0]
    html += "</a>#{word[5]}\n"
  end
  html += "</html>\n"
  html
end
