package relation_linking;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import com.ibm.icu.text.DecimalFormat;

import DP_entity_linking.dataset.*;
import net.didion.jwnl.JWNLException;

public class RelationLinkingEngine {

	public enum METHOD_MAPPING_TYPE {
		DIRECT, GLOVE, WORDNET;
	}

	public enum METHOD_DETECTION_TYPE {
		ALL, OPENIE, LEXICALPARSER, QUERYSTRIPPING;
	}

	private boolean directCheck = true;
	private boolean checkGlove = true;
	private boolean checkWordNet = true;

	private boolean withOpenIE = true;
	private boolean withLexicalParser = true;
	private boolean withQueryStripping = true;
	private boolean withEveryWord = true;
	private boolean allOverSimilarity = true;

	private double similarity = 0.1;
	private int firstResults = 3;

	private String datasetPath = "src/main/resources/data/webquestionsRelation.json";
	private String dbPediaOntologyPath = "src/main/resources/data/dbpedia_2015-04.nt";
	private String gloveModelPath = "/Users/fjuras/OneDriveBusiness/DPResources/glove.6B/glove.6B.300d.txt";
	private String lexicalParserModel = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
	private String JWNLPropertiesPath = "src/main/resources/data/file_properties.xml";
	private String entitySearchResultsFilePath = "src/main/resources/data/resultsWebquestions.txt";

	private String csvOutputPath = "/Users/fjuras/OneDriveBusiness/DPResources/Relations.csv";
	private String trainOutputPath = "/Users/fjuras/OneDriveBusiness/DPResources/trainSet";
	private String testOutputPath = "/Users/fjuras/OneDriveBusiness/DPResources/testSet";

	private String outputUtteranceKey = "utterance";
	private String outputRelationKey = "relation";
	private String outputDetectedKey = "detected";
	private String outputFoundRelationsKey = "number of found";
	private String outputDetectedRelationsKey = "number of detected";
	private String outputFromDetectedKey = "detected from";
	private String outputDetectedForKey = "detected for";
	private String outputFromDetectedAllKey = "detected from complete";
	private String outputSeparator = ";";
	private String outputDirectKey = "Direct";
	private String outputGloveLexicalKey = "GloVe_Lexical";
	private String outputGloveOpenIEKey = "GloVe_OpenIE";
	private String outputGloveStrippingKey = "GloVe_QuerryStripping";
	private String outputGloveAllKey = "GloVe_All";
	private String outputWordNetLexicalKey = "WordNet_Lexical";
	private String outputWordNetOpenIEKey = "WordNet_OpenIE";
	private String outputWordNetStrippingKey = "WordNet_QuerryStripping";
	private String outputWordNetAllKey = "WordNet_All";
	private String outputTrueValue = "1";
	private String outputFalseValue = "0";
	private String outputNotFoundValue = "-1";
	private String outputNewLine = "\n";

	private String outputTrainSeparator = " ";
	private String outputCategory = "|a";
	private String outputTrainValueSeparator = ":";

	private static DBPediaOntologyExtractor doe = null;
	private static FBCategoriesExtractor fce = null;

	private FileWriter csvOutput;
	private FileWriter trainOutput;
	private FileWriter testOutput;

	private DirectSearchEngine dse = null;
	private GloVeEngine glove = null;
	private WordNetEngine wordnet = null;

	boolean testStarted = false;
	private double precision = 0;
	private double recall = 0;
	private int TP = 0;
	private int tTP = 0;
	private int FP = 0;
	private int tFP = 0;
	private int FN = 0;
	private int tFN = 0;

	public RelationLinkingEngine() {
	}

	public static void main(String[] args)
			throws ClassNotFoundException, IOException, JWNLException, InterruptedException {

		RelationLinkingEngine rle = new RelationLinkingEngine();
		rle.runDetection();
		rle.calculateXGBoostStatistics();
	}

	private void runDetection() throws IOException, ClassNotFoundException, JWNLException, InterruptedException {
		System.out.println("Reading dataset...");
		DataSet dataset = new DataSet(datasetPath);
		List<Record> records = dataset.loadWebquestions();

		csvOutput = new FileWriter(csvOutputPath);
		trainOutput = new FileWriter(trainOutputPath);
		testOutput = new FileWriter(testOutputPath);
		printCSVRow(outputUtteranceKey, outputRelationKey, outputDirectKey, outputGloveLexicalKey, outputGloveOpenIEKey,
				outputGloveStrippingKey, outputGloveAllKey, outputWordNetLexicalKey, outputWordNetOpenIEKey,
				outputWordNetStrippingKey, outputWordNetAllKey, outputDetectedKey, outputDetectedRelationsKey,
				outputFoundRelationsKey, outputFromDetectedKey, outputDetectedForKey, outputFromDetectedAllKey);

		doe = new DBPediaOntologyExtractor(dbPediaOntologyPath);
		fce = new FBCategoriesExtractor();

		LexicalParsingEngine lpe = null;
		OpenIEEngine openIE = null;
		QueryStrippingEngine qse = null;
		if (withLexicalParser)
			lpe = new LexicalParsingEngine(lexicalParserModel);
		if (withOpenIE)
			openIE = new OpenIEEngine();
		if (withQueryStripping)
			qse = new QueryStrippingEngine(entitySearchResultsFilePath);

		if (directCheck)
			dse = new DirectSearchEngine();

		if (checkGlove) {
			glove = GloVeEngine.getInstance();
			if (withLexicalParser) {
				glove.init(gloveModelPath, similarity, lpe, allOverSimilarity);
			}
			if (withOpenIE) {
				glove.init(gloveModelPath, similarity, openIE, allOverSimilarity);
			}
			if (withQueryStripping) {
				glove.init(gloveModelPath, similarity, qse, allOverSimilarity);
			}
			if (withEveryWord) {
				glove.init(gloveModelPath, similarity, allOverSimilarity);
			}
		}

		if (checkWordNet) {
			wordnet = WordNetEngine.getInstance();
			if (withLexicalParser) {
				wordnet.init(JWNLPropertiesPath, lpe, similarity);
			}
			if (withOpenIE) {
				wordnet.init(JWNLPropertiesPath, openIE, similarity);
			}
			if (withQueryStripping) {
				wordnet.init(JWNLPropertiesPath, qse, similarity);
			}

			if (withEveryWord) {
				wordnet.init(JWNLPropertiesPath, similarity);
			}
		}

		int r = 0;
		for (Record record : records) {
			System.out.println(r + ":Processing utterance: " + record.getUtterance());

			Map<String, Result> results = new HashMap<String, Result>();
			if (directCheck)
				results.putAll(addFoundRelations(dse.getRelations(record.getUtterance()), results,
						METHOD_MAPPING_TYPE.DIRECT, null, record));

			if (checkGlove) {
				if (withLexicalParser) {
					results.putAll(addFoundRelations(
							glove.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.LEXICALPARSER), results,
							METHOD_MAPPING_TYPE.GLOVE, METHOD_DETECTION_TYPE.LEXICALPARSER, record));
				}
				if (withOpenIE) {
					results.putAll(
							addFoundRelations(glove.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.OPENIE),
									results, METHOD_MAPPING_TYPE.GLOVE, METHOD_DETECTION_TYPE.OPENIE, record));
				}
				if (withQueryStripping) {
					results.putAll(addFoundRelations(
							glove.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.QUERYSTRIPPING), results,
							METHOD_MAPPING_TYPE.GLOVE, METHOD_DETECTION_TYPE.QUERYSTRIPPING, record));
				}

				if (withEveryWord) {
					results.putAll(
							addFoundRelations(glove.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.ALL),
									results, METHOD_MAPPING_TYPE.GLOVE, METHOD_DETECTION_TYPE.ALL, record));
				}
			}
			if (checkWordNet) {
				if (withLexicalParser) {
					results.putAll(addFoundRelations(
							wordnet.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.LEXICALPARSER), results,
							METHOD_MAPPING_TYPE.WORDNET, METHOD_DETECTION_TYPE.LEXICALPARSER, record));
				}
				if (withOpenIE) {
					results.putAll(
							addFoundRelations(wordnet.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.OPENIE),
									results, METHOD_MAPPING_TYPE.WORDNET, METHOD_DETECTION_TYPE.OPENIE, record));
				}
				if (withQueryStripping) {
					results.putAll(addFoundRelations(
							wordnet.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.QUERYSTRIPPING), results,
							METHOD_MAPPING_TYPE.WORDNET, METHOD_DETECTION_TYPE.QUERYSTRIPPING, record));
				}

				if (withEveryWord) {
					results.putAll(
							addFoundRelations(wordnet.getRelations(record.getUtterance(), METHOD_DETECTION_TYPE.ALL),
									results, METHOD_MAPPING_TYPE.WORDNET, METHOD_DETECTION_TYPE.ALL, record));
				}
			}

			if (r < 3 * records.size() / 4) {
				printFoundRelations(results, record.getUtterance(), trainOutput);
			} else {
				if (!testStarted) {
					tTP = 0;
					tFP = 0;
					tFN = 0;
					testStarted = true;
				}
				printFoundRelations(results, record.getUtterance(), testOutput);
			}

			r++;
		}

		System.out.println();
		precision = ((double) TP / ((double) TP + (double) FP));
		recall = ((double) TP / ((double) TP + (double) FN));
		System.out.println("Precision = " + precision);
		System.out.println("Recall = " + recall);
		System.out.println("F1 = " + (2 * ((precision * recall) / (precision + recall))));
		System.out.println();
		precision = ((double) tTP / ((double) tTP + (double) tFP));
		recall = ((double) tTP / ((double) tTP + (double) tFN));
		System.out.println("Test Precision = " + precision);
		System.out.println("Test Recall = " + recall);
		System.out.println("Test F1 = " + (2 * ((precision * recall) / (precision + recall))));

		csvOutput.flush();
		csvOutput.close();
		trainOutput.flush();
		trainOutput.close();
		testOutput.flush();
		testOutput.close();

	}

	private void calculateXGBoostStatistics() throws IOException {
		FileReader test = new FileReader("/Users/fjuras/Downloads/xgboost-0.47/testedTrain");
		FileReader pred = new FileReader("/Users/fjuras/Downloads/xgboost-0.47/predicted.txt");

		@SuppressWarnings("resource")
		BufferedReader brT = new BufferedReader(test);
		@SuppressWarnings("resource")
		BufferedReader brP = new BufferedReader(pred);
		String lineT;
		String lineP;
		while ((lineT = brT.readLine()) != null) {
			lineP = brP.readLine();
			if (lineT.startsWith("1")) {
				if (lineP.startsWith("0")) {
					FN++;
				} else {
					TP++;
				}
			} else {
				if (lineP.startsWith("1")) {
					FP++;
				}
			}
		}
		System.out.println();
		precision = ((double) TP / ((double) TP + (double) FP));
		recall = ((double) TP / ((double) TP + (double) FN));
		System.out.println("Precision = " + precision);
		System.out.println("Recall = " + recall);
		System.out.println("F1 = " + (2 * ((precision * recall) / (precision + recall))));
		System.out.println();
	}

	private void printCSVRow(String utteranceValue, String relationValue, String directValue, String gloveLexicalValue,
			String gloveOpenIEValue, String gloveStrippingValue, String gloveAllValue, String wordNetLexicalValue,
			String wordNetOpenIEValue, String wordNetStrippingValue, String wordNetAllValue, String detectedValue,
			String foundValue, String detectedNumberValue, String outputFromDetectedValue,
			String outputDetectedForValue, String outputFromDetectedAllValue) throws IOException {

		csvOutput.append(utteranceValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(relationValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(directValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(gloveLexicalValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(gloveOpenIEValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(gloveStrippingValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(gloveAllValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(wordNetLexicalValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(wordNetOpenIEValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(wordNetStrippingValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(wordNetAllValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(detectedValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(foundValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(detectedNumberValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(outputFromDetectedAllValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(outputFromDetectedValue);
		csvOutput.append(outputSeparator);
		csvOutput.append(outputDetectedForValue);
		csvOutput.append(outputNewLine);
	}

	private void printTrainRow(boolean found, String relationName, Double direct, Double gloveLexical,
			Double gloveOpenIE, Double gloveStripping, Double gloveAll, Double wordnetLexical, Double wordnetOpenIE,
			Double wordnetStripping, Double wordnetAll, FileWriter output) throws IOException {

		DecimalFormat formatter = new DecimalFormat("#0.00");

		if (found)
			output.append(outputTrueValue);
		else
			output.append(outputNotFoundValue);
		output.append(outputTrainSeparator);
		output.append(outputCategory);
		output.append(outputTrainSeparator);
		output.append(relationName);
		output.append(outputTrainSeparator);
		output.append(outputDirectKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(direct));
		output.append(outputTrainSeparator);
		output.append(outputGloveLexicalKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(gloveLexical));
		output.append(outputTrainSeparator);
		output.append(outputGloveOpenIEKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(gloveOpenIE));
		output.append(outputTrainSeparator);
		output.append(outputGloveStrippingKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(gloveStripping));
		output.append(outputTrainSeparator);
		output.append(outputGloveAllKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(gloveAll));
		output.append(outputTrainSeparator);
		output.append(outputWordNetLexicalKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(wordnetLexical));
		output.append(outputTrainSeparator);
		output.append(outputWordNetOpenIEKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(wordnetOpenIE));
		output.append(outputTrainSeparator);
		output.append(outputWordNetStrippingKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(wordnetStripping));
		output.append(outputTrainSeparator);
		output.append(outputWordNetAllKey);
		output.append(outputTrainValueSeparator);
		output.append(formatter.format(wordnetAll));
		output.append(outputNewLine);
	}

	private int getNumberOfDetected(Map<String, Result> results) {
		int detected = 0;
		for (Entry<String, Result> result : results.entrySet()) {
			if (result.getValue().isDetected()) {
				detected++;
			}
		}
		return detected;
	}

	private String valueForBool(boolean bool) {
		return bool ? outputTrueValue : outputFalseValue;
	}

	private void printFoundRelations(Map<String, Result> results, String utterance, FileWriter output)
			throws IOException {
		System.out.println("Printing relations...");

		int numberOfDetected = getNumberOfDetected(results);
		int numberOfFound = results.size();

		if (results.isEmpty()) {
			printCSVRow(utterance, outputNotFoundValue, outputNotFoundValue, outputNotFoundValue, outputNotFoundValue,
					outputNotFoundValue, outputNotFoundValue, outputNotFoundValue, outputNotFoundValue,
					outputNotFoundValue, outputNotFoundValue, outputNotFoundValue, String.valueOf(numberOfDetected),
					String.valueOf(numberOfFound), outputNotFoundValue, outputNotFoundValue, outputNotFoundValue);
		} else {
			for (Entry<String, Result> relation : results.entrySet()) {
				Result result = relation.getValue();
				DecimalFormat formatter = new DecimalFormat("#0.00");
				printCSVRow(utterance, result.getName(), formatter.format(result.getDirectSearch()),
						formatter.format(result.getGloveLexicalParserSimilarity()),
						formatter.format(result.getGloveOpenIESimilarity()),
						formatter.format(result.getGloveStrippingSimilarity()),
						formatter.format(result.getGloveAllSimilarity()),
						formatter.format(result.getWordnetLexicalParserSimilarity()),
						formatter.format(result.getWordnetOpenIESimilarity()),
						formatter.format(result.getWordnetStrippingSimilarity()),
						formatter.format(result.getWordnetAllSimilarity()), valueForBool(result.isDetected()),
						String.valueOf(numberOfDetected), String.valueOf(numberOfFound),
						result.getNumberOfRelations().toString(), result.getNumberOfAllRelations().toString(),
						result.getDetectedFor());
				printTrainRow(result.isDetected(), result.getName(), result.getDirectSearch(),
						result.getGloveLexicalParserSimilarity(), result.getGloveOpenIESimilarity(),
						result.getGloveStrippingSimilarity(), result.getGloveAllSimilarity(),
						result.getWordnetLexicalParserSimilarity(), result.getWordnetOpenIESimilarity(),
						result.getWordnetStrippingSimilarity(), result.getWordnetAllSimilarity(), output);
			}
		}
	}

	private boolean isRelationDetected(String relation, Record record) {
		Map<String, ArrayList<String>> relations = record.getRelations();

		for (Entry<String, ArrayList<String>> rel : relations.entrySet()) {
			for (String r : rel.getValue())
				if (r.toLowerCase().compareTo(relation.toLowerCase()) == 0) {
					return true;
				}
		}
		FP++;
		tFP++;
		return false;
	}

	private Integer getNumberOfRelations(Record record, boolean all) {
		Map<String, ArrayList<String>> relations = record.getRelations();
		if (all)
			return new Integer(relations.size());

		Integer number = new Integer(0);
		for (Entry<String, ArrayList<String>> entry : relations.entrySet()) {
			number += entry.getValue().size();
		}
		return number;
	}

	private String getDetectedFor(Record record, String relation) {
		Map<String, ArrayList<String>> relations = record.getRelations();

		for (Entry<String, ArrayList<String>> entry : relations.entrySet()) {
			for (String rel : entry.getValue())
				if (rel.toLowerCase().equals(relation.toLowerCase()))
					return entry.getKey();
		}
		return null;
	}

	private ArrayList<String> getRelationsToDetect(Record record) {
		ArrayList<String> rel = new ArrayList<String>();
		for (Entry<String, ArrayList<String>> r : record.getRelations().entrySet()) {
			rel.add(r.getKey().toString());
		}
		return rel;
	}

	private int getUndetected(ArrayList<String> rel) {
		return rel.size();
	}

	@SuppressWarnings("hiding")
	<String, Double extends Comparable<? super Double>> List<Entry<String, Double>> entriesSortedByValues(
			Map<String, Double> map) {

		List<Entry<String, Double>> sortedEntries = new ArrayList<Entry<String, Double>>(map.entrySet());

		Collections.sort(sortedEntries, new Comparator<Entry<String, Double>>() {
			@Override
			public int compare(Entry<String, Double> e1, Entry<String, Double> e2) {
				return e2.getValue().compareTo(e1.getValue());
			}
		});

		return sortedEntries;
	}

	private Map<String, Result> addFoundRelations(Map<String, Double> relations, Map<String, Result> results,
			METHOD_MAPPING_TYPE mappingType, METHOD_DETECTION_TYPE detectionType, Record record) {

		List<Entry<String, Double>> sortedRelations = entriesSortedByValues(relations);

		ArrayList<String> relationsToDetect = getRelationsToDetect(record);

		Result result;
		int r = 0;

		for (Entry<String, Double> relation : sortedRelations) {
			if (r == firstResults || relationsToDetect.isEmpty())
				break;
			if (relation.getKey() == null)
				continue;
			if (!results.isEmpty() && results.containsKey(relation.getKey().toLowerCase())) {
				result = results.get(relation.getKey().toLowerCase());
				switch (mappingType) {
				case DIRECT:
					result.setDirectSearch(relation.getValue());
					break;
				case GLOVE: {
					switch (detectionType) {
					case ALL:
						result.setGloveAllSimilarity(relation.getValue());
						break;
					case OPENIE:
						result.setGloveOpenIESimilarity(relation.getValue());
						break;
					case LEXICALPARSER:
						result.setGloveLexicalParserSimilarity(relation.getValue());
						break;
					case QUERYSTRIPPING:
						result.setGloveStrippingSimilarity(relation.getValue());
						break;
					default:
						break;
					}
				}
					break;
				case WORDNET:
					switch (detectionType) {
					case ALL:
						result.setWordnetAllSimilarity(relation.getValue());
						break;
					case OPENIE:
						result.setWordnetOpenIESimilarity(relation.getValue());
						break;
					case LEXICALPARSER:
						result.setWordnetLexicalParserSimilarity(relation.getValue());
						break;
					case QUERYSTRIPPING:
						result.setWordnetStrippingSimilarity(relation.getValue());
						break;
					default:
						break;
					}
					break;
				}
			} else {
				result = new Result(relation.getKey(), mappingType, detectionType, relation.getValue());
				boolean detected = isRelationDetected(relation.getKey(), record);
				result.setDetected(detected);
				String detFor = getDetectedFor(record, relation.getKey());
				if (detected) {
					TP++;
					tTP++;
					if (!relationsToDetect.remove(detFor)) {
						TP--;
						tTP--;
					}
				}
				result.setDetectedFor(detFor);
				results.put(relation.getKey().toLowerCase(), result);
			}

			result.setNumberOfRelations(getNumberOfRelations(record, false));
			result.setNumberOfAllRelations(getNumberOfRelations(record, true));
			results.replace(relation.getKey().toLowerCase(), result);
			r++;
		}

		FN += getUndetected(relationsToDetect);
		tFN += getUndetected(relationsToDetect);

		return results;
	}

	public static DBPediaOntologyExtractor getDBPediaOntologyExtractor() {
		return doe;
	}

	public static FBCategoriesExtractor getFBCategoriesExtractor() {
		return fce;
	}
}
