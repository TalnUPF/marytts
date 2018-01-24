package marytts.modules.acoustic;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import marytts.datatypes.MaryXML;
import marytts.util.MaryUtils;
import marytts.util.dom.DomUtils;
import marytts.util.dom.MaryDomUtils;
import marytts.util.dom.NameNodeFilter;
import marytts.util.math.MathUtils;
import marytts.util.math.Polynomial;
import marytts.util.string.StringUtils;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.NodeIterator;
import org.w3c.dom.traversal.TreeWalker;

/**
 * This module will apply prosody modifications to the already predicted values (dur and f0) in the acoustparams.
 * 
 * @author Peiro-Lilja Alex
 * 
 */

public class ProsodyParagraphHandler {
	
	private int F0CONTOUR_LENGTH = 101; // Assumption: the length of f0 contour of a prosody element is 101 (0,1,2....100)
	// DONOT change this number as some index numbers are based on this number
	private DecimalFormat df;
	private Logger logger = MaryUtils.getLogger("ProsodyParagraphHandler");
	public static int CurrentParagraphProsody = 1;

	public ProsodyParagraphHandler() {
		df = (DecimalFormat) NumberFormat.getNumberInstance(Locale.US);
		df.applyPattern("#0.0");
	}
	
	/**
	 * A method to modify speaker rate, mean and range of the pitch according to the paragraph-based structure features shown in
	 * "Paragraph-based Prosodic Cues for Speech Synthesis Applications"
	 * 
	 * @param doc
	 *            - MARY XML Document
	 * 
	 */
	
	public void process(Document doc) {
		
		NodeList sentences = doc.getElementsByTagName(MaryXML.SENTENCE);
		String sentenceLocation;
		//int NumOfMiddleSentences = sentences.getLength()-2;
		int NumOfMiddleSentence = 0;
		for (int k = 0; k < sentences.getLength(); k++) {
			// And now the actual processing of each sentence
			logger.debug("Processing next sentence");
			if(k == 0) {
				sentenceLocation = "First";
			} else if(k == (sentences.getLength() - 1)) {
				sentenceLocation = "Last";
			} else {
				sentenceLocation = "Middle";
				NumOfMiddleSentence++;
			}
			Element sentence = (Element) sentences.item(k);
			NodeList phrases = sentence.getElementsByTagName(MaryXML.PHRASE);
			for (int m = 0; m < phrases.getLength(); m++) {
				int numOfWords = 0;
				Element phrase = (Element) phrases.item(m);
				NodeList nl = phrase.getElementsByTagName("ph"); //ph elements
				NodeList tkns = phrase.getElementsByTagName(MaryXML.TOKEN);
				int SumLengthWords = 0;
				for (int t = 0; t < tkns.getLength(); t++) {
					Element el = (Element) tkns.item(t);
					// origText.length() will give us the length of the current word
					if(!(el.getAttribute("pos").equals("."))) {
						numOfWords++;
						//The method to extract text from a token!!
						String origText = MaryDomUtils.tokenText(el);
						SumLengthWords += origText.length();
					}
				}
				
				//Ratio used as a factor to control the weight of the relative speaker rate difference (%)
				// The value 5 has been chosen based on a work shown in:
				// http://hearle.nahoo.net/Academic/Maths/Sentence.html
				double MnWordLengthRatio = ((double) SumLengthWords/(double) numOfWords)/5;
				System.out.println("Number of words of this phrase: " + numOfWords);
				double PhraseLengthRatio = (double) (numOfWords)/2; //18
				// Here we implement a specific speech rate according to the paper's knowledge
				modifySpeechRate(nl,numOfWords, sentenceLocation, MnWordLengthRatio, PhraseLengthRatio);
				
				// Here we implement a pitch and range modification according to the paper's knowledge
				double[] f0Contour = getF0Contour(nl);
				double[] coeffs = Polynomial.fitPolynomial(f0Contour, 1);
				double[] baseF0Contour = Polynomial.generatePolynomialValues(coeffs, F0CONTOUR_LENGTH, 0, 1);
				double[] diffF0Contour = new double[F0CONTOUR_LENGTH];
				
				// Should obtain or the first or the last position of the base contour, as it is order 1. So we can figure out
				// whether the polynomial representation increases or decreases. We force the range to decrease to simulate the 
				// behavior in a paragraph evolution.
				double maximumValue = MathUtils.max(baseF0Contour);
				double maxLocationBaseF0Contour = MathUtils.findGlobalPeakLocation(baseF0Contour);
				
				// Extract base contour from original contour
				for (int i = 0; i < f0Contour.length; i++) {
					diffF0Contour[i] = f0Contour[i] - baseF0Contour[i];
				}
				
				// Set pitch modifications to base contour (first order polynomial fit contour)
				baseF0Contour = applyBasePitchModification(baseF0Contour, maximumValue, maxLocationBaseF0Contour, sentenceLocation,numOfWords);
				
				// Now, imposing back the diff. contour
				for (int i = 0; i < f0Contour.length; i++) {
					f0Contour[i] = diffF0Contour[i] + baseF0Contour[i];
				}

				setModifiedContour(nl, f0Contour);	
				
			}
		}

	}

	/**
	 * apply given pitch specifications to the base contour
	 * 
	 * @param baseF0Contour
	 *            baseF0Contour
	 * @return baseF0Contour
	 */
	private double[] applyBasePitchModification(double[] baseF0Contour, double maxValue, double maxLocation, String sentenceLocation, int numOfWords) {
		
		// Here we are going to return the new baseF0Contour
		// The newBaseF0Contour will be created based on a % of range we need and a linear distribution for all the new samples.
		
		double[] newBaseF0Contour = new double[F0CONTOUR_LENGTH];
		double pitchHalfRange = 0.0;
		if(sentenceLocation.equals("First")) {
			if (numOfWords <= 4) {
				pitchHalfRange = 0.072; //0.065
			} else if (numOfWords > 4 && numOfWords <= 8) {
				pitchHalfRange = 0.069; //0.062
			} else {
				pitchHalfRange = 0.065; //0.059
			}
		} else if(sentenceLocation.equals("Middle")) {
			if (numOfWords <= 4) {
				pitchHalfRange = 0.029; //0.025
			} else if (numOfWords > 4 && numOfWords <= 8) {
				pitchHalfRange = 0.027; //0.022
			} else {
				pitchHalfRange = 0.025; //0.02
			}
		} else if(sentenceLocation.equals("Last")) {
			if (numOfWords <= 4) {
				pitchHalfRange = 0.048; //0.037
			} else if (numOfWords > 4 && numOfWords <= 8) {
				pitchHalfRange = 0.046; //0.035
			} else {
				pitchHalfRange = 0.044; //0.03
			}
		} else {
			throw new IllegalArgumentException("Sentence has to be located");
		}
		//System.out.println("Maximum value from base contour: " + maxValue);
		double meanBaseF0Contour = MathUtils.mean(baseF0Contour);
		// Linear equation to compute the new BaseF0Contour
		// newBaseF0Contour = baseF0Contour;
		for (int x = 0; x < baseF0Contour.length; x++) {
			newBaseF0Contour[x] = ((meanBaseF0Contour - maxValue - maxValue*pitchHalfRange)/51)*x + (maxValue + maxValue*pitchHalfRange); 
		}

		return newBaseF0Contour;
	}

	/**
	 * To set duration specifications according to 'rate' requirements
	 * 
	 * @param nl
	 *            - NodeList of 'ph' elements; All elements in this NodeList should be 'ph' elements only All these 'ph' elements
	 *            should contain 'd', 'end' attributes            
	 */
	private void modifySpeechRate(NodeList nl, int numOfWords, String location, double ratio, double phraseRatio) {

		assert nl != null;
		assert numOfWords > 0;
		//double timeLengthPhrase = 0.0; //the result of all ms that sum up the total number of 'ph' in the current phrase
		double outputSpeakerRate = 0.0; // In words/second
		//double maxSpeakerRate = 3.8; // Maximum of words/second (it is arbitrary)
		//double minSpeakerRate = 2.7;

		
		for (int i = 0; i < nl.getLength(); i++) {
			//These elements should be 'ph'
			Element e = (Element) nl.item(i);
			assert "ph".equals(e.getNodeName()) : "NodeList should contain 'ph' elements only";
			if (!e.hasAttribute("d")) {
				continue;
			}
			//double durAttribute = new Double(e.getAttribute("d")).doubleValue();
			//timeLengthPhrase += durAttribute;
		}
		
		//Convert timeLengthPhrase into seconds (the durations are in ms)
		//timeLengthPhrase = timeLengthPhrase/(double) 1000;
		//Obtaining the rate words/second
		//double wordsPerSecond = numOfWords/timeLengthPhrase;
		// Using the location of the sentence into the paragraph to put the prior knowledge about the speaker rate
		if (CurrentParagraphProsody < MaryXML.NUM_PARAGRAPHS) {
			if (location.equals("First")) {	
				if(numOfWords <= 4) {
					outputSpeakerRate = 8.7;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 9.5; 
				} else {
					outputSpeakerRate = 9.9; // original value
				}
			} else if (location.equals("Middle")) {
				if(numOfWords <= 4) {
					outputSpeakerRate = 2.8;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 3; 
				} else {
					outputSpeakerRate = 3.3;
				}
			} else if (location.equals("Last")) {
				if(numOfWords <= 4) {
					outputSpeakerRate = 6;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 6.9; // original value
				} else {
					outputSpeakerRate = 7.6;
				}
			}
			CurrentParagraphProsody++;
		} else {
			double factorrate = 0.041;
			if (location.equals("First")) {	
				if(numOfWords <= 4) {
					outputSpeakerRate = 8.7-8.7*factorrate;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 9.5-9.5*factorrate; 
				} else {
					outputSpeakerRate = 9.9-9.9*factorrate; // original value
				}
			} else if (location.equals("Middle")) {
				if(numOfWords <= 4) {
					outputSpeakerRate = 2.8-2.8*factorrate;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 3-3*factorrate; 
				} else {
					outputSpeakerRate = 3.3-3.3*factorrate;
				}
			} else if (location.equals("Last")) {
				if(numOfWords <= 4) {
					outputSpeakerRate = 6-6*factorrate;
				} else if(numOfWords > 4 && numOfWords < 8) {
					outputSpeakerRate = 6.9-6.9*factorrate; // original value
				} else {
					outputSpeakerRate = 7.6-7.6*factorrate;
				}
			}
			CurrentParagraphProsody = 1;
		}
		

		// Compute the relative difference between the current rate and the desired
		//double relativeDifference = ((outputSpeakerRate - wordsPerSecond)/wordsPerSecond);
		// Apply this relativeDifference percentage into the current ph durations
		for (int i = 0; i < nl.getLength(); i++) {
			//These elements should be 'ph'
			Element e = (Element) nl.item(i);
			assert "ph".equals(e.getNodeName()) : "NodeList should contain 'ph' elements only";
			if (!e.hasAttribute("d")) {
				continue;
			}
			double newDurAttribute;
			double durAttribute = new Double(e.getAttribute("d")).doubleValue();
			newDurAttribute = durAttribute + ((outputSpeakerRate / 100) * durAttribute);
			//newDurAttribute = durAttribute + (relativeDifference * durAttribute * ratio * phraseRatio);
			e.setAttribute("d", newDurAttribute + "");
			//System.out.println(durAttribute+" = " +newDurAttribute);
		}

		Element e = (Element) nl.item(0);

		Element rootElement = e.getOwnerDocument().getDocumentElement();
		NodeIterator nit = MaryDomUtils.createNodeIterator(rootElement, MaryXML.PHONE, MaryXML.BOUNDARY);
		Element nd;
		double duration = 0.0;
		for (int i = 0; (nd = (Element) nit.nextNode()) != null; i++) {
			if ("boundary".equals(nd.getNodeName())) {
				if (nd.hasAttribute("duration")) {
					duration += new Double(nd.getAttribute("duration")).doubleValue();
				}
			} else {
				if (nd.hasAttribute("d")) {
					duration += new Double(nd.getAttribute("d")).doubleValue();
				}
			}
			double endTime = 0.001 * duration;
			if (!nd.getNodeName().equals(MaryXML.BOUNDARY)) {
				// setting "end" attr for boundaries does not have the intended effect, since elsewhere, only the "duration" attr
				// is used for boundaries
				nd.setAttribute("end", String.valueOf(endTime));
			}
			// System.out.println(nd.getNodeName()+" = " +nd.getAttribute("end"));
		}

	}

	// we need a public getter with variable array size to call from unitselection.analysis
	private double[] getF0Contour(NodeList nl) {
		return getF0Contour(nl, F0CONTOUR_LENGTH); // Assume contour has F0CONTOUR_LENGTH frames
	}

	/**
	 * To get a continuous pitch contour from nodelist of "ph" elements
	 * 
	 * @param nl
	 *            - NodeList of 'ph' elements; All elements in this NodeList should be 'ph' elements only All these 'ph' elements
	 *            should contain 'd', 'end' attributes
	 * @param arraysize
	 *            the length of the output pitch contour array (arraysize &gt; 0)
	 * @return a double array of pitch contour
	 * @throws IllegalArgumentException
	 *             if NodeList is null or it contains elements other than 'ph' elements
	 * @throws IllegalArgumentException
	 *             if given 'ph' elements do not contain 'd' or 'end' attributes
	 * @throws IllegalArgumentException
	 *             if given arraysize is not greater than zero
	 */
	public double[] getF0Contour(NodeList nl, int arraysize) {

		if (nl == null || nl.getLength() == 0) {
			throw new IllegalArgumentException("Input NodeList should not be null or zero length list");
		}
		if (arraysize <= 0) {
			throw new IllegalArgumentException("Given arraysize should be is greater than zero");
		}

		// A sanity checker for NodeList: for 'ph' elements only condition
		for (int i = 0; i < nl.getLength(); i++) {
			Element e = (Element) nl.item(i);
			if (!"ph".equals(e.getNodeName())) {
				throw new IllegalArgumentException("Input NodeList should contain 'ph' elements only");
			}
			if (!e.hasAttribute("d") || !e.hasAttribute("end")) {
				throw new IllegalArgumentException("All 'ph' elements should contain 'd' and 'end' attributes");
			}
		}

		Element firstElement = (Element) nl.item(0);
		Element lastElement = (Element) nl.item(nl.getLength() - 1);

		double[] contour = new double[arraysize];
		Arrays.fill(contour, 0.0);

		double fEnd = (new Double(firstElement.getAttribute("end"))).doubleValue();
		double fDuration = 0.001 * (new Double(firstElement.getAttribute("d"))).doubleValue();
		double lEnd = (new Double(lastElement.getAttribute("end"))).doubleValue();
		double fStart = fEnd - fDuration; // 'prosody' tag starting point
		double duration = lEnd - fStart; // duration of 'prosody' modification request

		for (int i = 0; i < nl.getLength(); i++) {
			Element e = (Element) nl.item(i);
			String f0Attribute = e.getAttribute("f0");

			if (f0Attribute == null || "".equals(f0Attribute)) {
				continue;
			}

			double phoneEndTime = (new Double(e.getAttribute("end"))).doubleValue();
			double phoneDuration = 0.001 * (new Double(e.getAttribute("d"))).doubleValue();
			// double localStartTime = endTime - phoneDuration;

			int[] f0Targets = StringUtils.parseIntPairs(e.getAttribute("f0"));

			for (int j = 0, len = f0Targets.length / 2; j < len; j++) {
				int percent = f0Targets[2 * j];
				int f0Value = f0Targets[2 * j + 1];
				double partPhone = phoneDuration * (percent / 100.0);
				int placeIndex = (int) Math.floor(((((phoneEndTime - phoneDuration) - fStart) + partPhone) * arraysize)
						/ (double) duration);
				if (placeIndex >= arraysize) {
					placeIndex = arraysize - 1;
				} else if (placeIndex < 0) {
					placeIndex = 0;
				}
				contour[placeIndex] = f0Value;
			}
		}

		return MathUtils.interpolateNonZeroValues(contour);
	}

	/**
	 * To set new modified contour into XML
	 * 
	 * @param nl
	 *            nl
	 * @param contour
	 *            contour
	 */
	private void setModifiedContour(NodeList nl, double[] contour) {

		Element firstElement = (Element) nl.item(0);
		Element lastElement = (Element) nl.item(nl.getLength() - 1);

		double fEnd = (new Double(firstElement.getAttribute("end"))).doubleValue();
		double fDuration = 0.001 * (new Double(firstElement.getAttribute("d"))).doubleValue();
		double lEnd = (new Double(lastElement.getAttribute("end"))).doubleValue();
		double fStart = fEnd - fDuration; // 'prosody' tag starting point
		double duration = lEnd - fStart; // duaration of 'prosody' modification request

		Map<Integer, Integer> f0Map;

		for (int i = 0; i < nl.getLength(); i++) {

			Element e = (Element) nl.item(i);
			String f0Attribute = e.getAttribute("f0");

			if (f0Attribute == null || "".equals(f0Attribute)) {
				continue;
			}

			double phoneEndTime = (new Double(e.getAttribute("end"))).doubleValue();
			double phoneDuration = 0.001 * (new Double(e.getAttribute("d"))).doubleValue();

			Pattern p = Pattern.compile("(\\d+,\\d+)");

			// Split input with the pattern
			Matcher m = p.matcher(e.getAttribute("f0"));
			String setF0String = "";
			while (m.find()) {
				String[] f0Values = (m.group().trim()).split(",");
				Integer percent = new Integer(f0Values[0]);
				Integer f0Value = new Integer(f0Values[1]);
				double partPhone = phoneDuration * (percent.doubleValue() / 100.0);

				int placeIndex = (int) Math.floor(((((phoneEndTime - phoneDuration) - fStart) + partPhone) * F0CONTOUR_LENGTH)
						/ (double) duration);
				if (placeIndex >= F0CONTOUR_LENGTH) {
					placeIndex = F0CONTOUR_LENGTH - 1;
				}
				setF0String = setF0String + "(" + percent + "," + (int) contour[placeIndex] + ")";

			}

			e.setAttribute("f0", setF0String);
		}
	}

	/**
	 * To get prosody contour specifications by parsing 'contour' attribute values
	 * 
	 * @param attribute
	 *            - 'contour' attribute, it should not be null Expected format: '(0%, +10%)(50%,+30%)(95%,-10%)'
	 * @return HashMap that contains prosody contour specifications it returns empty map if given attribute is not in expected
	 *         format
	 */
	//private Map<String, String> getContourSpecifications(String attribute) {

		//assert attribute != null;
		//assert !"".equals(attribute) : "given attribute should not be empty string";

		//Map<String, String> f0Map = new HashMap<String, String>();
		//Pattern p = Pattern
				//.compile("\\(\\s*[0-9]+(.[0-9]+)?[%]\\s*,\\s*(x-low|low|medium|high|x-high|default|[+|-]?[0-9]+(.[0-9]+)?(%|Hz|st)?)\\s*\\)");

		// Split input with the pattern
		//Matcher m = p.matcher(attribute);
		//while (m.find()) {
			// System.out.println(m.group());
			//String singlePair = m.group().trim();
			//String[] f0Values = singlePair.substring(1, singlePair.length() - 1).split(",");
			//f0Map.put(f0Values[0].trim(), f0Values[1].trim());
		//}
		//return f0Map;
	//}

	/**
	 * mapping a fixed value to a relative value
	 * 
	 * @param pitchAttribute
	 *            pitchAttribute
	 * @param baseF0Contour
	 *            baseF0Contour
	 * @return "+" + df.format((relative - 100)) + "%" if relative > 100, "-" + df.format((100 - relative)) + "%" otherwise
	 */
	//private String fixedValue2RelativeValue(String pitchAttribute, double[] baseF0Contour) {

		//pitchAttribute = pitchAttribute.substring(0, pitchAttribute.length() - 2);
		//double fixedValue = (new Float(pitchAttribute)).doubleValue();
		//double meanValue = MathUtils.mean(baseF0Contour);
		//double relative = (100.0 * fixedValue) / meanValue;
		//if (relative > 100) {
			//return "+" + df.format((relative - 100)) + "%";
		//}

		//return "-" + df.format((100 - relative)) + "%";
	//}

	/**
	 * mapping a positive 'rate' integer to a relative value
	 * 
	 * @param rateAttribute
	 *            rateAttribute
	 * @return "+" + df.format((relativePercentage - 100)) + "%" if relativePercentage > 100, "-" + df.format((100 -
	 *         relativePercentage)) + "%" otherwise
	 */
	//private String positiveInteger2RelativeValues(String rateAttribute) {

		//double positiveNumber = (new Float(rateAttribute)).doubleValue();
		//double relativePercentage = (positiveNumber * 100.0);

		//if (relativePercentage > 100) {
			//return "+" + df.format((relativePercentage - 100)) + "%";
		//}

		//return "-" + df.format((100 - relativePercentage)) + "%";
	//}

	/**
	 * a look-up table for mapping rate labels to relative values
	 * 
	 * @param rateAttribute
	 *            rateAttribute
	 * @return "-50%" if rateAttribute equals "x-slow", "-33.3%" if rateAttribute equals "slow", "+0%" if rateAttribute equals
	 *         "medium", "+33%" if rateAttribute equals "fast", "+100%" if rateAttribute equals "x-fast", "+0%" otherwise
	 */
	//private String rateLabels2RelativeValues(String rateAttribute) {

		//if (rateAttribute.equals("x-slow")) {
			//return "-50%";
		//} else if (rateAttribute.equals("slow")) {
			//return "-33.3%";
		//} else if (rateAttribute.equals("medium")) {
			//return "+0%";
		//} else if (rateAttribute.equals("fast")) {
			//return "+33%";
		//} else if (rateAttribute.equals("x-fast")) {
			//return "+100%";
		//}

		//return "+0%";
	//}

}
