/**
 * Copyright 2010 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package marytts.modules.acoustic;

import java.io.InputStream;
import java.util.List;

import marytts.features.FeatureProcessorManager;
import marytts.unitselection.select.Target;
//import marytts.datatypes.MaryXML;

import org.w3c.dom.Element;

/**
 * Model which currently predicts only a flat 400 ms duration for each boundary Element
 * <p>
 * Could be replaced by a PauseTree or something else, but that would require a CARTModel instead of this.
 * 
 * @author steiner
 * 
 */
public class BoundaryModel extends Model {
	public BoundaryModel(FeatureProcessorManager featureManager, String voiceName, InputStream dataStream,
			String targetAttributeName, String targetAttributeFormat, String featureName, String predictFrom, String applyTo) {
		super(featureManager, voiceName, dataStream, targetAttributeName, targetAttributeFormat, featureName, predictFrom,
				applyTo);
	}

	@Override
	public void applyTo(List<Element> elements) {
		for (Element element : elements) {
			
			if (!element.hasAttribute(targetAttributeName)) { // targetAttributeName is "duration"
				if(element.getAttribute("breakindex").equals("4")) {
					element.setAttribute(targetAttributeName, "240"); //240 //290
				} else if (element.getAttribute("breakindex").equals("5")) {
					element.setAttribute(targetAttributeName, "520"); //520 //490
				} else if (element.getAttribute("breakindex").equals("6")) {
					element.setAttribute(targetAttributeName, "1240"); //1239
				} else if (element.getAttribute("breakindex").equals("7")) {
					element.setAttribute(targetAttributeName, "1240"); //1239
				} else if (element.getAttribute("breakindex").equals("8")) {
					element.setAttribute(targetAttributeName, "1240"); //1239
				} else if (element.getAttribute("breakindex").equals("9")) {
					element.setAttribute(targetAttributeName, "300"); //300
				} else
					element.setAttribute(targetAttributeName,"210");
			}
			
			/*if (!element.hasAttribute(targetAttributeName)) {
                element.setAttribute(targetAttributeName, "400");
            }
            */
		}
	}

	/**
	 * For boundaries, this does nothing;
	 */
	@Override
	protected float evaluate(Target target) {
		return Float.NaN;
	}

	/**
	 * For boundaries, this does nothing;
	 */
	@Override
	public void loadData() {
		return;
	}
}
