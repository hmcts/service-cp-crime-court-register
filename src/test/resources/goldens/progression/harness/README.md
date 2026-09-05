# The recording harness

Reference copies of the two classes that recorded the goldens beside this file. They are **not** on
the test classpath and nothing in `src/test/java` compiles them: they are here so a reader can see
exactly what was run, and so a re-record is a repeat rather than a reconstruction.

`GoldenRecorder` is the recorder. `CourtRegisterHandlerRule` carries
`CourtRegisterHandler.getDefendantType` (`:131-153`) and `CourtRegisterHandler.getCourtApplicationId`
(`:226-235`) verbatim, because `CourtRegisterHandler` itself cannot be compiled outside progression.

The PDF payload class is **not** copied. `GoldenRecorder` compiles the real
`uk.gov.moj.cpp.progression.processor.CourtRegisterPdfPayloadGenerator` straight out of the
progression working tree, which is the whole point of recording rather than hand-writing the goldens.

## Re-recording

`PROG` is a checkout of `cpp-context-progression` at the commit named in `../PROVENANCE.md`, and
`REPO` is a checkout of this repository. Progression is **not** built: the four jars come from the
`target/dependency` directories a previous `mvn dependency:copy-dependencies` already left there, and
the two progression sources are compiled straight from the working tree.

```bash
PROG=/path/to/cpp-context-progression
REPO=/path/to/service-cp-crime-court-register
JDK=/usr/lib/jvm/java-17-openjdk           # progression is a JDK 17 build; this repo's own Java 25
                                           # toolchain is not used here and must not be
HANDLER=$PROG/progression-command/progression-command-handler/target/dependency
API=$PROG/progression-command/progression-command-api/target/dependency

CP=$API/javax.json-1.1.4.jar:$HANDLER/utilities-core-17.103.0.jar:$HANDLER/guava-32.1.3-jre.jar
CP=$CP:$HANDLER/commons-lang3-3.12.0.jar:$HANDLER/commons-collections-3.2.2.jar
CP=$CP:$HANDLER/progression-domain-message-17.0.270-SNAPSHOT.jar

mkdir -p /tmp/cr-goldens/classes
$JDK/bin/javac -encoding UTF-8 -nowarn -cp "$CP" -d /tmp/cr-goldens/classes \
  $PROG/progression-domain/progression-domain-common/src/main/java/uk/gov/moj/cpp/progression/domain/constant/DateTimeFormats.java \
  $PROG/progression-event/progression-event-processor/src/main/java/uk/gov/moj/cpp/progression/processor/CourtRegisterPdfPayloadGenerator.java \
  $REPO/src/test/resources/goldens/progression/harness/CourtRegisterHandlerRule.java \
  $REPO/src/test/resources/goldens/progression/harness/GoldenRecorder.java

TZ=Europe/London $JDK/bin/java -Duser.timezone=Europe/London \
  -cp "/tmp/cr-goldens/classes:$CP" GoldenRecorder "$REPO"
```

The recorder overwrites `../pdf-payload/`, `../defendant-type/*.json` and `../INDEX.json`. It reads
`../defendant-type/synthetic/` and never writes there: those six inputs are authored, not recorded.

`cases[].age` is the one field a re-record can legitimately move, because
`CourtRegisterPdfPayloadGenerator.getAge` (`:323-329`) reads `LocalDate.now()`. `../PROVENANCE.md`
says which date this set was recorded on and what a test has to do about it. Any **other** difference
between two recordings is a non-determinism to investigate, not to re-record over.
