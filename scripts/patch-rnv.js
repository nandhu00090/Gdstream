const fs = require('fs');
const path = require('path');

const targets = [
  path.join(
    __dirname,
    '../node_modules/react-native-video/android/src/main/java/com/brentvatne/exoplayer/ReactExoplayerView.java',
  ),
  path.join(__dirname, '../rnv-inspect/ReactExoplayerView.java'),
];

const oldSnippet = `                    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                        TrackGroup group = groups.get(groupIndex);
                        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                            Format format = group.getFormat(trackIndex);
                            
                            boolean isMatch = false;
                            if ("language".equals(type) && format.language != null && format.language.equals(value)) {
                                isMatch = true;
                            } else if ("title".equals(type) && format.label != null && format.label.equals(value)) {
                                isMatch = true;
                            } else if ("index".equals(type)) {
                                int targetIndex = ReactBridgeUtils.safeParseInt(value, -1);
                                if (targetIndex == trackIndex) {
                                    isMatch = true;
                                }
                            }`;

const newSnippet = `                    int flatTrackIndex = 0;
                    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                        TrackGroup group = groups.get(groupIndex);
                        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                            Format format = group.getFormat(trackIndex);
                            
                            boolean isMatch = false;
                            if ("language".equals(type) && format.language != null && format.language.equals(value)) {
                                isMatch = true;
                            } else if ("title".equals(type) && format.label != null && format.label.equals(value)) {
                                isMatch = true;
                            } else if ("index".equals(type)) {
                                int targetIndex = ReactBridgeUtils.safeParseInt(value, -1);
                                if (targetIndex == flatTrackIndex) {
                                    isMatch = true;
                                }
                            }
                            flatTrackIndex++;`;

for (const file of targets) {
  if (fs.existsSync(file)) {
    const content = fs.readFileSync(file, 'utf8');
    if (content.includes(oldSnippet)) {
      fs.writeFileSync(file, content.replace(oldSnippet, newSnippet), 'utf8');
      console.log('Patched subtitle track index selection in:', file);
    }
  }
}
