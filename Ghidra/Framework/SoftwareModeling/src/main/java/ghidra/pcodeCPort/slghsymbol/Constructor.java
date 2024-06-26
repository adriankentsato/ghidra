/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.pcodeCPort.slghsymbol;

import static ghidra.pcode.utils.SlaFormat.*;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;

import generic.stl.IteratorSTL;
import generic.stl.VectorSTL;
import ghidra.pcodeCPort.context.SleighError;
import ghidra.pcodeCPort.semantics.ConstTpl.const_type;
import ghidra.pcodeCPort.semantics.ConstructTpl;
import ghidra.pcodeCPort.semantics.HandleTpl;
import ghidra.pcodeCPort.slghpatexpress.*;
import ghidra.program.model.pcode.Encoder;
import ghidra.sleigh.grammar.Location;

public class Constructor {
	public final Location location;
	private TokenPattern pattern;
	private SubtableSymbol parent;
	private PatternEquation pateq;
	private VectorSTL<OperandSymbol> operands = new VectorSTL<>();
	private VectorSTL<String> printpiece = new VectorSTL<>();

	// Context commands
	private VectorSTL<ContextChange> context = new VectorSTL<>();
	private ConstructTpl templ;
	private VectorSTL<ConstructTpl> namedtempl; // Other named p-code sections
	private int minimumlength; // Minimum length taken up by this constructor in bytes
	private long id; // Unique id of constructor within subtable
	private int firstwhitespace; // Index of first whitespace piece in -printpiece-
	private int flowthruindex; // if >=0 then print only a single operand no markup
	private boolean inerror;
	private int sourceFileIndex = -1;    //source file index

	public TokenPattern getPattern() {
		return pattern;
	}

	public String getFilename() {
		return location == null ? "(null)" : location.filename;
	}

	public void setMinimumLength(int l) {
		minimumlength = l;
	}

	public int getMinimumLength() {
		return minimumlength;
	}

	public void setId(long i) {
		id = i;
	}

	public long getId() {
		return id;
	}

	public int getLineno() {
		return location == null ? 0 : location.lineno;
	}

	/**
	 * Set the source file index
	 * @param index index
	 */
	public void setSourceFileIndex(int index) {
		sourceFileIndex = index;
	}

	/**
	 * Return the source file index
	 * @return index
	 */
	public int getIndex() {
		return sourceFileIndex;
	}

	public void addContext(VectorSTL<ContextChange> vec) {
		context = vec;
	}

	public SubtableSymbol getParent() {
		return parent;
	}

	public int getNumOperands() {
		return operands.size();
	}

	public OperandSymbol getOperand(int i) {
		return operands.get(i);
	}

	public PatternEquation getPatternEquation() {
		return pateq;
	}

	public ConstructTpl getTempl() {
		return templ;
	}

	public ConstructTpl getNamedTempl(int secnum) {
		if (secnum < namedtempl.size()) {
			return namedtempl.get(secnum);
		}
		return null;
	}

	public int getNumSections() {
		return namedtempl.size();
	}

	public void markSubtableOperands(VectorSTL<Integer> check) {
		// Adjust -check- so it has one entry for every operand, a 0 if it is a subtable, a 2 if it is not
		check.resize(operands.size(), 0);
		for (int i = 0; i < operands.size(); ++i) {
			TripleSymbol sym = operands.get(i).getDefiningSymbol();
			if ((sym != null) && (sym.getType() == symbol_type.subtable_symbol)) {
				check.set(i, 0);
			}
			else {
				check.set(i, 2);
			}
		}
	}

	public void collectLocalExports(ArrayList<Long> results) {
		if (templ == null) {
			return;
		}
		HandleTpl handle = templ.getResult();
		if (handle == null) {
			return;
		}
		if (handle.getSpace().isConstSpace()) {
			return;	// Even if the value is dynamic, the pointed to value won't get used
		}
		if (handle.getPtrSpace().getType() != const_type.real) {
			if (handle.getTempSpace().isUniqueSpace()) {
				results.add(handle.getTempOffset().getReal());
			}
			return;
		}
		if (handle.getSpace().isUniqueSpace()) {
			results.add(handle.getPtrOffset().getReal());
			return;
		}
		if (handle.getSpace().getType() == const_type.handle) {
			int handleIndex = handle.getSpace().getHandleIndex();
			OperandSymbol opSym = getOperand(handleIndex);
			opSym.collectLocalValues(results);
		}
	}

	public void setError(boolean val) {
		inerror = val;
	}

	public boolean isError() {
		return inerror;
	}

	public boolean isRecursive() {
		// Does this constructor cause recursion with its table
		for (int i = 0; i < operands.size(); ++i) {
			TripleSymbol sym = operands.get(i).getDefiningSymbol();
			if (sym == parent) {
				return true;
			}
		}
		return false;
	}

	public Constructor(Location location) {
		this.location = location;
		pattern = null;
		parent = null;
		pateq = null;
		templ = null;
		namedtempl = new VectorSTL<>();
		firstwhitespace = -1;
		flowthruindex = -1;
		inerror = false;
	}

	public Constructor(Location location, SubtableSymbol p) {
		this.location = location;
		pattern = null;
		parent = p;
		pateq = null;
		templ = null;
		namedtempl = new VectorSTL<>();
		firstwhitespace = -1;
		inerror = false;
	}

	public void dispose() {
		if (pattern != null) {
			pattern.dispose();
		}
		if (pateq != null) {
			PatternEquation.release(pateq);
		}
		if (templ != null) {
			templ.dispose();
		}
		IteratorSTL<ContextChange> iter;
		for (iter = context.begin(); !iter.isEnd(); iter.increment()) {
			iter.get().dispose();
		}
	}

	public void addInvisibleOperand(OperandSymbol sym) {
		operands.push_back(sym);
	}

	public void addOperand(OperandSymbol sym) {
		String operstring = "\n" + ((char) ('A' + operands.size())); // Indicater
		// character
		// for
		// operand
		// Encode index of operand
		operands.push_back(sym);
		printpiece.push_back(operstring); // Placeholder for operand's string
	}

	public void addSyntax(String syn) {
		if (!syn.isEmpty() && syn.trim().isEmpty()) {
			// Normalize whitespace to a single space
			syn = " ";
		}
		if (firstwhitespace == -1 && " ".equals(syn)) {
			firstwhitespace = printpiece.size();
		}
		if (syn.isEmpty()) {
			return;
		}
		if (printpiece.empty()) {
			printpiece.push_back(syn);
		}
		else if (" ".equals(printpiece.back()) && " ".equals(syn)) {
			// Don't add more whitespace
		}
		else if (printpiece.back().charAt(0) == '\n' || " ".equals(printpiece.back()) ||
			" ".equals(syn)) {
			printpiece.push_back(syn);
		}
		else {
			String push = printpiece.pop_back() + syn;
			if ("".equals(push)) {
				push = " ";
			}
			printpiece.push_back(push);
		}
	}

	public void addEquation(PatternEquation pe) {
		(pateq = pe).layClaim();
	}

	public void setMainSection(ConstructTpl tpl) {
		templ = tpl;
	}

	public void setNamedSection(ConstructTpl tpl, int id) {
		// Add a named section to the constructor
		while (namedtempl.size() <= id) {
			namedtempl.push_back(null);
		}
		namedtempl.set(id, tpl);
	}

	// Allow for user to force extra space at end of printing
	public void removeTrailingSpace() {
		if ((!printpiece.empty()) && (printpiece.back().equals(" "))) {
			printpiece.pop_back();
		}
		// while((!printpiece.empty())&&(printpiece.back()==" "))
		// printpiece.pop_back();
	}

	public void encode(Encoder encoder) throws IOException {
		encoder.openElement(ELEM_CONSTRUCTOR);
		encoder.writeUnsignedInteger(ATTRIB_PARENT, parent.getId());
		encoder.writeSignedInteger(ATTRIB_FIRST, firstwhitespace);
		encoder.writeSignedInteger(ATTRIB_LENGTH, minimumlength);
		encoder.writeSignedInteger(ATTRIB_SOURCE, sourceFileIndex);
		encoder.writeSignedInteger(ATTRIB_LINE, getLineno());
		for (int i = 0; i < operands.size(); ++i) {
			encoder.openElement(ELEM_OPER);
			encoder.writeUnsignedInteger(ATTRIB_ID, operands.get(i).getId());
			encoder.closeElement(ELEM_OPER);
		}
		final int printpieces = printpiece.size();
		for (int i = 0; i < printpieces; ++i) {
			String piece = printpiece.get(i);
			if (piece.length() > 0 && piece.charAt(0) == '\n') {
				int index = piece.charAt(1) - 'A';
				encoder.openElement(ELEM_OPPRINT);
				encoder.writeSignedInteger(ATTRIB_ID, index);
				encoder.closeElement(ELEM_OPPRINT);
			}
			else {
				encoder.openElement(ELEM_PRINT);
				encoder.writeString(ATTRIB_PIECE, piece);
				encoder.closeElement(ELEM_PRINT);
			}
		}
		for (int i = 0; i < context.size(); ++i) {
			context.get(i).encode(encoder);
		}
		if (templ != null) {
			templ.encode(encoder, -1);
		}
		for (int i = 0; i < namedtempl.size(); ++i) {
			if (namedtempl.get(i) == null) {
				continue;
			}
			namedtempl.get(i).encode(encoder, i);
		}
		encoder.closeElement(ELEM_CONSTRUCTOR);
	}

	private void orderOperands() {
		OperandSymbol sym;

		VectorSTL<OperandSymbol> patternorder = new VectorSTL<>();
		// New order of the operands
		VectorSTL<OperandSymbol> newops = new VectorSTL<>();
		int lastsize;

		pateq.operandOrder(this, patternorder);
		for (int i = 0; i < operands.size(); ++i) { // Make sure patternorder contains all operands
			sym = operands.get(i);
			if (!sym.isMarked()) {
				patternorder.push_back(sym);
				sym.setMark(); // Make sure all operands are marked
			}
		}
		do {
			lastsize = newops.size();
			for (int i = 0; i < patternorder.size(); ++i) {
				sym = patternorder.get(i);
				if (!sym.isMarked()) {
					// "unmarked" means it is already in newops
					continue;
				}
				if (sym.isOffsetIrrelevant()) {
					// expression Operands come last
					continue;
				}
				if ((sym.offsetbase == -1) || (!operands.get(sym.offsetbase).isMarked())) {
					newops.push_back(sym);
					sym.clearMark();
				}
			}
		}
		while (newops.size() != lastsize);

		// Tack on expression Operands
		for (int i = 0; i < patternorder.size(); ++i) {
			sym = patternorder.get(i);
			if (sym.isOffsetIrrelevant()) {
				newops.push_back(sym);
				sym.clearMark();
			}
		}

		if (newops.size() != operands.size()) {
			throw new SleighError("Circular offset dependency between operands", location);
		}

		for (int i = 0; i < newops.size(); ++i) { // Fix up operand indices
			newops.get(i).hand = i;
			newops.get(i).localexp.changeIndex(i);
		}
		VectorSTL<Integer> handmap = new VectorSTL<>(operands.size()); // Create
		// index
		// translation map
		for (int i = 0; i < operands.size(); ++i) {
			handmap.push_back(operands.get(i).hand);
		}

		// Fix up offsetbase
		for (int i = 0; i < newops.size(); ++i) {
			sym = newops.get(i);
			if (sym.offsetbase == -1) {
				continue;
			}
			sym.offsetbase = handmap.get(sym.offsetbase);
		}
		// Fix up templates
		if (templ != null) {
			templ.changeHandleIndex(handmap);
		}
		for (int i = 0; i < namedtempl.size(); ++i) {
			ConstructTpl ntempl = namedtempl.get(i);
			if (ntempl != null) {
				ntempl.changeHandleIndex(handmap);
			}
		}
		// Fix up printpiece operand refs
		for (int i = 0; i < printpiece.size(); ++i) {
			final String piece = printpiece.get(i);
			if (piece.length() > 0 && piece.charAt(0) == '\n') {
				int index = piece.charAt(1) - 'A';
				index = handmap.get(index);
				char c = (char) ('A' + index);
				printpiece.set(i, "\n" + c);
			}
		}
		operands = newops;
	}

	TokenPattern buildPattern(PrintStream s) {
		if (pattern != null) {
			return pattern; // Already built
		}

		pattern = new TokenPattern(location);
		VectorSTL<TokenPattern> oppattern = new VectorSTL<>();
		boolean recursion = false;
		// Generate pattern for each operand, store in oppattern
		for (int i = 0; i < operands.size(); ++i) {
			OperandSymbol sym = operands.get(i);
			TripleSymbol triple = sym.getDefiningSymbol();
			PatternExpression defexp = sym.getDefiningExpression();
			if (triple != null) {
				if (triple instanceof SubtableSymbol) {
					SubtableSymbol subsym = (SubtableSymbol) triple;
					if (subsym.isBeingBuilt()) { // Detected recursion
						if (recursion) {
							throw new SleighError("Illegal recursion", location);
						}
						// We should also check that recursion is rightmost
						// extreme
						recursion = true;
						oppattern.push_back(new TokenPattern(location));
					}
					else {
						oppattern.push_back(subsym.buildPattern(s));
					}
				}
				else {
					oppattern.push_back(triple.getPatternExpression().genMinPattern(oppattern));
				}
			}
			else if (defexp != null) {
				TokenPattern tmppat = defexp.genMinPattern(oppattern);
				if (null == tmppat) {
					throw new SleighError("operand " + sym.getName() + " has an issue", location);
				}
				oppattern.push_back(tmppat);
			}
			else {
				throw new SleighError("operand " + sym.getName() + " is undefined", location);
			}
			TokenPattern sympat = oppattern.back();
			sym.minimumlength = sympat.getMinimumLength();
			if (sympat.getLeftEllipsis() || sympat.getRightEllipsis()) {
				sym.setVariableLength();
			}
		}

		if (pateq == null) {
			throw new SleighError("Missing equation", location);
		}
		// Build the entire pattern
		pateq.genPattern(oppattern);
		pattern = new TokenPattern(location, pateq.getTokenPattern());
		if (pattern.alwaysFalse()) {
			throw new SleighError("Impossible pattern--always false", location);
		}
		if (recursion) {
			pattern.setRightEllipsis(true);
		}
		minimumlength = pattern.getMinimumLength(); // Get length of the pattern
		// in bytes

		OperandResolve resolve = new OperandResolve(operands);
		if (!pateq.resolveOperandLeft(resolve)) {
			throw new SleighError("Unable to resolve operand offsets", location);
		}

		// Unravel relative offsets to absolute (if possible)
		for (int i = 0; i < operands.size(); ++i) {
			int base, offset;
			OperandSymbol sym = operands.get(i);
			if (sym.isOffsetIrrelevant()) {
				sym.offsetbase = -1;
				sym.reloffset = 0;
				continue;
			}
			base = sym.offsetbase;
			offset = sym.reloffset;
			while (base >= 0) {
				sym = operands.get(base);
				if (sym.isVariableLength()) {
					// Cannot resolve to absolute
					break;
				}
				base = sym.offsetbase;
				offset += sym.getMinimumLength();
				offset += sym.reloffset;
				if (base < 0) {
					operands.get(i).offsetbase = base;
					operands.get(i).reloffset = offset;
				}
			}
		}

		// Make sure context expressions are valid
		for (int i = 0; i < context.size(); ++i) {
			context.get(i).validate();
		}

		orderOperands(); // Order the operands based on offset dependency
		return pattern;
	}

	String detailedName() {
//        if (printpiece != null && printpiece.size() > 0) {
//            return "(" + printpiece.get(0) + ") ";
//        }
		return "";
	}

	// Print identifying information about constructor
	// for use in error messages
	public void printInfo(PrintStream s) {
		s.append("table \"").append(parent.getName());
		s.append("\" constructor " + detailedName() + "from " + location);
	}

	@Override
	public String toString() {
		return "table \"" + parent.getName() + "\" constructor " + detailedName() + "from " +
			location;
	}
}
