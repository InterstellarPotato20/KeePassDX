/*
 * Copyright 2017 Brian Pellin, Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePass DX.
 *
 *  KeePass DX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePass DX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePass DX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.save;

import android.util.Log;
import android.util.Xml;
import biz.source_code.base64Coder.Base64Coder;
import com.kunzisoft.keepass.crypto.CipherFactory;
import com.kunzisoft.keepass.crypto.PwStreamCipherFactory;
import com.kunzisoft.keepass.crypto.engine.CipherEngine;
import com.kunzisoft.keepass.crypto.keyDerivation.KdfEngine;
import com.kunzisoft.keepass.crypto.keyDerivation.KdfFactory;
import com.kunzisoft.keepass.database.*;
import com.kunzisoft.keepass.database.element.*;
import com.kunzisoft.keepass.database.exception.PwDbOutputException;
import com.kunzisoft.keepass.database.exception.UnknownKDF;
import com.kunzisoft.keepass.database.security.ProtectedBinary;
import com.kunzisoft.keepass.database.security.ProtectedString;
import com.kunzisoft.keepass.stream.HashedBlockOutputStream;
import com.kunzisoft.keepass.stream.HmacBlockOutputStream;
import com.kunzisoft.keepass.stream.LEDataOutputStream;
import com.kunzisoft.keepass.utils.DateUtil;
import com.kunzisoft.keepass.utils.EmptyUtils;
import com.kunzisoft.keepass.utils.MemUtil;
import com.kunzisoft.keepass.utils.Types;
import org.joda.time.DateTime;
import org.spongycastle.crypto.StreamCipher;
import org.xmlpull.v1.XmlSerializer;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.Map.Entry;
import java.util.zip.GZIPOutputStream;

public class PwDbV4Output extends PwDbOutput<PwDbHeaderV4> {
    private static final String TAG = PwDbV4Output.class.getName();

	private PwDatabaseV4 mPM;
	private StreamCipher randomStream;
	private XmlSerializer xml;
	private PwDbHeaderV4 header;
	private byte[] hashOfHeader;
	private byte[] headerHmac;
    private CipherEngine engine = null;

	public PwDbV4Output(PwDatabaseV4 pm, OutputStream os) {
		super(os);
		this.mPM = pm;
	}

	@Override
	public void output() throws PwDbOutputException {

        try {
			try {
				engine = CipherFactory.getInstance(mPM.getDataCipher());
			} catch (NoSuchAlgorithmException e) {
				throw new PwDbOutputException("No such cipher", e);
			}

			header = outputHeader(mOS);

			OutputStream osPlain;
			if (header.getVersion() < PwDbHeaderV4.FILE_VERSION_32_4) {
				CipherOutputStream cos = attachStreamEncryptor(header, mOS);
				cos.write(header.streamStartBytes);

				osPlain = new HashedBlockOutputStream(cos);
			} else {
				mOS.write(hashOfHeader);
				mOS.write(headerHmac);

				HmacBlockOutputStream hbos = new HmacBlockOutputStream(mOS, mPM.getHmacKey());
				osPlain = attachStreamEncryptor(header, hbos);
			}

			OutputStream osXml;
			try {
				if (mPM.getCompressionAlgorithm() == PwCompressionAlgorithm.Gzip) {
					osXml = new GZIPOutputStream(osPlain);
				} else {
					osXml = osPlain;
				}

				if (header.getVersion() >= PwDbHeaderV4.FILE_VERSION_32_4) {
					PwDbInnerHeaderOutputV4 ihOut =  new PwDbInnerHeaderOutputV4(mPM, header, osXml);
                    ihOut.output();
				}

				outputDatabase(osXml);
				osXml.close();
			} catch (IllegalArgumentException e) {
				throw new PwDbOutputException(e);
			} catch (IllegalStateException e) {
				throw new PwDbOutputException(e);
			}
		} catch (IOException e) {
			throw new PwDbOutputException(e);
		}
	}
	
	private void outputDatabase(OutputStream os) throws IllegalArgumentException, IllegalStateException, IOException {

		xml = Xml.newSerializer();
		
		xml.setOutput(os, "UTF-8");
		xml.startDocument("UTF-8", true);
		
		xml.startTag(null, PwDatabaseV4XML.ElemDocNode);
		
		writeMeta();
		
		PwGroupV4 root = mPM.getRootGroup();
		xml.startTag(null, PwDatabaseV4XML.ElemRoot);
		startGroup(root);
		Stack<PwGroupV4> groupStack = new Stack<>();
		groupStack.push(root);

		if (!root.doForEachChild(
				new NodeHandler<PwEntryV4>() {
					@Override
					public boolean operate(PwEntryV4 entry) {
						try {
							writeEntry(entry, false);
						} catch (IOException ex) {
							throw new RuntimeException(ex);
						}

						return true;
					}
				},
				new NodeHandler<PwGroupV4>() {
					@Override
					public boolean operate(PwGroupV4 node) {
						while (true) {
							try {
								if (node.getParent() == groupStack.peek()) {
									groupStack.push(node);
									startGroup(node);
									break;
								} else {
									groupStack.pop();
									if (groupStack.size() <= 0) return false;
									endGroup();
								}
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}

						return true;
					}
				})) throw new RuntimeException("Writing groups failed");
		
		while (groupStack.size() > 1) {
			xml.endTag(null, PwDatabaseV4XML.ElemGroup);
			groupStack.pop();
		}
		
		endGroup();
		
		writeList(PwDatabaseV4XML.ElemDeletedObjects, mPM.getDeletedObjects());
		
		xml.endTag(null, PwDatabaseV4XML.ElemRoot);
		
		xml.endTag(null, PwDatabaseV4XML.ElemDocNode);
		xml.endDocument();
		
	}
	
	private void writeMeta() throws IllegalArgumentException, IllegalStateException, IOException {
		xml.startTag(null, PwDatabaseV4XML.ElemMeta);
		
		writeObject(PwDatabaseV4XML.ElemGenerator, mPM.localizedAppName);
		
		if (hashOfHeader != null) {
			writeObject(PwDatabaseV4XML.ElemHeaderHash, String.valueOf(Base64Coder.encode(hashOfHeader)));
		}
		
		writeObject(PwDatabaseV4XML.ElemDbName, mPM.getName(), true);
		writeObject(PwDatabaseV4XML.ElemDbNameChanged, mPM.getNameChanged().getDate());
		writeObject(PwDatabaseV4XML.ElemDbDesc, mPM.getDescription(), true);
		writeObject(PwDatabaseV4XML.ElemDbDescChanged, mPM.getDescriptionChanged().getDate());
		writeObject(PwDatabaseV4XML.ElemDbDefaultUser, mPM.getDefaultUserName(), true);
		writeObject(PwDatabaseV4XML.ElemDbDefaultUserChanged, mPM.getDefaultUserNameChanged().getDate());
		writeObject(PwDatabaseV4XML.ElemDbMntncHistoryDays, mPM.getMaintenanceHistoryDays());
		writeObject(PwDatabaseV4XML.ElemDbColor, mPM.getColor());
		writeObject(PwDatabaseV4XML.ElemDbKeyChanged, mPM.getKeyLastChanged().getDate());
		writeObject(PwDatabaseV4XML.ElemDbKeyChangeRec, mPM.getKeyChangeRecDays());
		writeObject(PwDatabaseV4XML.ElemDbKeyChangeForce, mPM.getKeyChangeForceDays());
		
		writeList(PwDatabaseV4XML.ElemMemoryProt, mPM.getMemoryProtection());
		
		writeCustomIconList();
		
		writeObject(PwDatabaseV4XML.ElemRecycleBinEnabled, mPM.isRecycleBinEnabled());
		writeObject(PwDatabaseV4XML.ElemRecycleBinUuid, mPM.getRecycleBinUUID());
		writeObject(PwDatabaseV4XML.ElemRecycleBinChanged, mPM.getRecycleBinChanged());
		writeObject(PwDatabaseV4XML.ElemEntryTemplatesGroup, mPM.getEntryTemplatesGroup());
		writeObject(PwDatabaseV4XML.ElemEntryTemplatesGroupChanged, mPM.getEntryTemplatesGroupChanged().getDate());
		writeObject(PwDatabaseV4XML.ElemHistoryMaxItems, mPM.getHistoryMaxItems());
		writeObject(PwDatabaseV4XML.ElemHistoryMaxSize, mPM.getHistoryMaxSize());
		writeObject(PwDatabaseV4XML.ElemLastSelectedGroup, mPM.getLastSelectedGroup());
		writeObject(PwDatabaseV4XML.ElemLastTopVisibleGroup, mPM.getLastTopVisibleGroup());

		if (header.getVersion() < PwDbHeaderV4.FILE_VERSION_32_4) {
			writeBinPool();
		}
		writeList(PwDatabaseV4XML.ElemCustomData, mPM.getCustomData());
		
		xml.endTag(null, PwDatabaseV4XML.ElemMeta);
		
	}
	
	private CipherOutputStream attachStreamEncryptor(PwDbHeaderV4 header, OutputStream os) throws PwDbOutputException {
		Cipher cipher;
		try {
			//mPM.makeFinalKey(header.masterSeed, mPM.kdfParameters);

			cipher = engine.getCipher(Cipher.ENCRYPT_MODE, mPM.getFinalKey(), header.getEncryptionIV());
		} catch (Exception e) {
			throw new PwDbOutputException("Invalid algorithm.", e);
		}

        return new CipherOutputStream(os, cipher);
	}

	@Override
	protected SecureRandom setIVs(PwDbHeaderV4 header) throws PwDbOutputException {
		SecureRandom random = super.setIVs(header);
		random.nextBytes(header.getMasterSeed());

		int ivLength = engine.ivLength();
		if (ivLength != header.getEncryptionIV().length) {
			header.setEncryptionIV(new byte[ivLength]);
		}
		random.nextBytes(header.getEncryptionIV());

		if (mPM.getKdfParameters() == null) {
			mPM.setKdfParameters(KdfFactory.aesKdf.getDefaultParameters());
		}

		try {
			KdfEngine kdf = KdfFactory.getEngineV4(mPM.getKdfParameters());
			kdf.randomize(mPM.getKdfParameters());
		} catch (UnknownKDF unknownKDF) {
            Log.e(TAG, "Unable to retrieve header", unknownKDF);
		}

		if (header.getVersion() < PwDbHeaderV4.FILE_VERSION_32_4) {
			header.innerRandomStream = CrsAlgorithm.Salsa20;
			header.innerRandomStreamKey = new byte[32];
		} else {
			header.innerRandomStream = CrsAlgorithm.ChaCha20;
			header.innerRandomStreamKey = new byte[64];
		}
		random.nextBytes(header.innerRandomStreamKey);

		randomStream = PwStreamCipherFactory.getInstance(header.innerRandomStream, header.innerRandomStreamKey);
		if (randomStream == null) {
			throw new PwDbOutputException("Invalid random cipher");
		}

		if ( header.getVersion() < PwDbHeaderV4.FILE_VERSION_32_4) {
			random.nextBytes(header.streamStartBytes);
		}
		
		return random;
	}
	
	@Override
	public PwDbHeaderV4 outputHeader(OutputStream os) throws PwDbOutputException {

        PwDbHeaderV4 header = new PwDbHeaderV4(mPM);
		setIVs(header);

		PwDbHeaderOutputV4 pho = new PwDbHeaderOutputV4(mPM, header, os);
		try {
			pho.output();
		} catch (IOException e) {
			throw new PwDbOutputException("Failed to output the header.", e);
		}
		
		hashOfHeader = pho.getHashOfHeader();
		headerHmac = pho.headerHmac;
		
		return header;
	}
	
	private void startGroup(PwGroupV4 group) throws IllegalArgumentException, IllegalStateException, IOException {
		xml.startTag(null, PwDatabaseV4XML.ElemGroup);
		writeObject(PwDatabaseV4XML.ElemUuid, group.getId());
		writeObject(PwDatabaseV4XML.ElemName, group.getTitle());
		writeObject(PwDatabaseV4XML.ElemNotes, group.getNotes());
		writeObject(PwDatabaseV4XML.ElemIcon, group.getIcon().getIconId());
		
		if (!group.getIconCustom().equals(PwIconCustom.Companion.getZERO())) {
			writeObject(PwDatabaseV4XML.ElemCustomIconID, group.getIconCustom().getUuid());
		}
		
		writeList(PwDatabaseV4XML.ElemTimes, group);
		writeObject(PwDatabaseV4XML.ElemIsExpanded, group.isExpanded());
		writeObject(PwDatabaseV4XML.ElemGroupDefaultAutoTypeSeq, group.getDefaultAutoTypeSequence());
		writeObject(PwDatabaseV4XML.ElemEnableAutoType, group.getEnableAutoType());
		writeObject(PwDatabaseV4XML.ElemEnableSearching, group.getEnableSearching());
		writeObject(PwDatabaseV4XML.ElemLastTopVisibleEntry, group.getLastTopVisibleEntry());
		
	}
	
	private void endGroup() throws IllegalArgumentException, IllegalStateException, IOException {
		xml.endTag(null, PwDatabaseV4XML.ElemGroup);
	}
	
	private void writeEntry(PwEntryV4 entry, boolean isHistory) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(entry != null);
		
		xml.startTag(null, PwDatabaseV4XML.ElemEntry);
		
		writeObject(PwDatabaseV4XML.ElemUuid, entry.getId());
		writeObject(PwDatabaseV4XML.ElemIcon, entry.getIcon().getIconId());
		
		if (!entry.getIconCustom().equals(PwIconCustom.Companion.getZERO())) {
			writeObject(PwDatabaseV4XML.ElemCustomIconID, entry.getIconCustom().getUuid());
		}
		
		writeObject(PwDatabaseV4XML.ElemFgColor, entry.getForegroundColor());
		writeObject(PwDatabaseV4XML.ElemBgColor, entry.getBackgroundColor());
		writeObject(PwDatabaseV4XML.ElemOverrideUrl, entry.getOverrideURL());
		writeObject(PwDatabaseV4XML.ElemTags, entry.getTags());
		
		writeList(PwDatabaseV4XML.ElemTimes, entry);
		
		writeList(entry.getFields().getListOfAllFields(), true);
		writeList(entry.getBinaries());
		writeList(PwDatabaseV4XML.ElemAutoType, entry.getAutoType());
		
		if (!isHistory) {
			writeList(PwDatabaseV4XML.ElemHistory, entry.getHistory(), true);
		}
		// else entry.sizeOfHistory() == 0
		
		xml.endTag(null, PwDatabaseV4XML.ElemEntry);
	}
	

	private void writeObject(String key, ProtectedBinary value) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(key != null && value != null);
		
		xml.startTag(null, PwDatabaseV4XML.ElemBinary);
		xml.startTag(null, PwDatabaseV4XML.ElemKey);
		xml.text(safeXmlString(key));
		xml.endTag(null, PwDatabaseV4XML.ElemKey);
		
		xml.startTag(null, PwDatabaseV4XML.ElemValue);
		int ref = mPM.getBinPool().findKey(value);
		String strRef = Integer.toString(ref);
		
		if (strRef != null) {
			xml.attribute(null, PwDatabaseV4XML.AttrRef, strRef);
		}
		else {
			subWriteValue(value);
		}
		xml.endTag(null, PwDatabaseV4XML.ElemValue);
		
		xml.endTag(null, PwDatabaseV4XML.ElemBinary);
	}

	/*
	TODO Make with pipe
	private void subWriteValue(ProtectedBinary value) throws IllegalArgumentException, IllegalStateException, IOException {
        try (InputStream inputStream = value.getData()) {
            if (inputStream == null) {
                Log.e(TAG, "Can't write a null input stream.");
                return;
            }

            if (value.isProtected()) {
                xml.attribute(null, PwDatabaseV4XML.AttrProtected, PwDatabaseV4XML.ValTrue);

                try (InputStream cypherInputStream =
                             IOUtil.pipe(inputStream,
                                     o -> new org.spongycastle.crypto.io.CipherOutputStream(o, randomStream))) {
                    writeInputStreamInBase64(cypherInputStream);
                }

            } else {
                if (mPM.getCompressionAlgorithm() == PwCompressionAlgorithm.Gzip) {

                    xml.attribute(null, PwDatabaseV4XML.AttrCompressed, PwDatabaseV4XML.ValTrue);

                    try (InputStream gZipInputStream =
                                 IOUtil.pipe(inputStream, GZIPOutputStream::new, (int) value.length())) {
                        writeInputStreamInBase64(gZipInputStream);
                    }

                } else {
                    writeInputStreamInBase64(inputStream);
                }
            }
        }
	}

	private void writeInputStreamInBase64(InputStream inputStream) throws IOException {
        try (InputStream base64InputStream =
                     IOUtil.pipe(inputStream,
                             o -> new Base64OutputStream(o, DEFAULT))) {
            MemUtil.readBytes(base64InputStream,
                    buffer -> xml.text(Arrays.toString(buffer)));
        }
    }
    //*/

    //*
    private void subWriteValue(ProtectedBinary value) throws IllegalArgumentException, IllegalStateException, IOException {

        int valLength = (int) value.length();
        if (valLength > 0) {
            byte[] buffer = new byte[valLength];
            if (valLength == value.getData().read(buffer, 0, valLength)) {

                if (value.isProtected()) {
                    xml.attribute(null, PwDatabaseV4XML.AttrProtected, PwDatabaseV4XML.ValTrue);

                    byte[] encoded = new byte[valLength];
                    randomStream.processBytes(buffer, 0, valLength, encoded, 0);
                    xml.text(String.valueOf(Base64Coder.encode(encoded)));

                } else {
                    if (mPM.getCompressionAlgorithm() == PwCompressionAlgorithm.Gzip) {
                        xml.attribute(null, PwDatabaseV4XML.AttrCompressed, PwDatabaseV4XML.ValTrue);

                        byte[] compressData = MemUtil.compress(buffer);
                        xml.text(String.valueOf(Base64Coder.encode(compressData)));

                    } else {
                        xml.text(String.valueOf(Base64Coder.encode(buffer)));
                    }
                }
            } else {
                Log.e(TAG, "Unable to read the stream of the protected binary");
            }
        }
    }
    //*/

	private void writeObject(String name, String value, boolean filterXmlChars) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && value != null);
		
		xml.startTag(null, name);
		
		if (filterXmlChars) {
			value = safeXmlString(value);
		}
		
		xml.text(value);
		xml.endTag(null, name);
	}
	
	private void writeObject(String name, String value) throws IllegalArgumentException, IllegalStateException, IOException {
		writeObject(name, value, false);
	}
	
	private void writeObject(String name, Date value) throws IllegalArgumentException, IllegalStateException, IOException {
		if (header.getVersion() < PwDbHeaderV4.FILE_VERSION_32_4) {
			writeObject(name, PwDatabaseV4XML.dateFormatter.get().format(value));
		} else {
			DateTime dt = new DateTime(value);
			long seconds = DateUtil.convertDateToKDBX4Time(dt);
			byte[] buf = LEDataOutputStream.writeLongBuf(seconds);
			String b64 = new String(Base64Coder.encode(buf));
			writeObject(name, b64);
		}

	}
	
	private void writeObject(String name, long value) throws IllegalArgumentException, IllegalStateException, IOException {
		writeObject(name, String.valueOf(value));
	}
	
	private void writeObject(String name, Boolean value) throws IllegalArgumentException, IllegalStateException, IOException {
		String text;
		if (value == null) {
			text = "null";
		}
		else if (value) {
			text = PwDatabaseV4XML.ValTrue;
		}
		else {
			text = PwDatabaseV4XML.ValFalse;
		}
		
		writeObject(name, text);
	}
	
	private void writeObject(String name, UUID uuid) throws IllegalArgumentException, IllegalStateException, IOException {
		byte[] data = Types.UUIDtoBytes(uuid);
		writeObject(name, String.valueOf(Base64Coder.encode(data)));
	}
	
	private void writeObject(String name, String keyName, String keyValue, String valueName, String valueValue) throws IllegalArgumentException, IllegalStateException, IOException {
		xml.startTag(null, name);
		
		xml.startTag(null, keyName);
		xml.text(safeXmlString(keyValue));
		xml.endTag(null, keyName);
		
		xml.startTag(null, valueName);
		xml.text(safeXmlString(valueValue));
		xml.endTag(null, valueName);
		
		xml.endTag(null, name);
	}
	
	private void writeList(String name, AutoType autoType) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && autoType != null);
		
		xml.startTag(null, name);
		
		writeObject(PwDatabaseV4XML.ElemAutoTypeEnabled, autoType.getEnabled());
		writeObject(PwDatabaseV4XML.ElemAutoTypeObfuscation, autoType.getObfuscationOptions());
		
		if (autoType.getDefaultSequence().length() > 0) {
			writeObject(PwDatabaseV4XML.ElemAutoTypeDefaultSeq, autoType.getDefaultSequence(), true);
		}
		
		for (Entry<String, String> pair : autoType.entrySet()) {
			writeObject(PwDatabaseV4XML.ElemAutoTypeItem, PwDatabaseV4XML.ElemWindow, pair.getKey(), PwDatabaseV4XML.ElemKeystrokeSequence, pair.getValue());
		}
		
		xml.endTag(null, name);
		
	}

	private void writeList(Map<String, ProtectedString> strings, boolean isEntryString) throws IllegalArgumentException, IllegalStateException, IOException {
		assert (strings != null);
		
		for (Entry<String, ProtectedString> pair : strings.entrySet()) {
			writeObject(pair.getKey(), pair.getValue(), isEntryString);
			
		}
		
	}

	private void writeObject(String key, ProtectedString value, boolean isEntryString) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(key !=null && value != null);
		
		xml.startTag(null, PwDatabaseV4XML.ElemString);
		xml.startTag(null, PwDatabaseV4XML.ElemKey);
		xml.text(safeXmlString(key));
		xml.endTag(null, PwDatabaseV4XML.ElemKey);
		
		xml.startTag(null, PwDatabaseV4XML.ElemValue);
		boolean protect = value.isProtected();
		if (isEntryString) {
			if (key.equals(MemoryProtectionConfig.ProtectDefinition.TITLE_FIELD)) {
				protect = mPM.getMemoryProtection().getProtectTitle();
			}
			else if (key.equals(MemoryProtectionConfig.ProtectDefinition.USERNAME_FIELD)) {
				protect = mPM.getMemoryProtection().getProtectUserName();
			}
			else if (key.equals(MemoryProtectionConfig.ProtectDefinition.PASSWORD_FIELD)) {
				protect = mPM.getMemoryProtection().getProtectPassword();
			}
			else if (key.equals(MemoryProtectionConfig.ProtectDefinition.URL_FIELD)) {
				protect = mPM.getMemoryProtection().getProtectUrl();
			}
			else if (key.equals(MemoryProtectionConfig.ProtectDefinition.NOTES_FIELD)) {
				protect = mPM.getMemoryProtection().getProtectNotes();
			}
		}
		
		if (protect) {
			xml.attribute(null, PwDatabaseV4XML.AttrProtected, PwDatabaseV4XML.ValTrue);
			
			byte[] data = value.toString().getBytes("UTF-8");
			int valLength = data.length;
			
			if (valLength > 0) {
				byte[] encoded = new byte[valLength];
				randomStream.processBytes(data, 0, valLength, encoded, 0);
				xml.text(String.valueOf(Base64Coder.encode(encoded)));
			}
		}
		else {
			xml.text(safeXmlString(value.toString()));
		}
		
		xml.endTag(null, PwDatabaseV4XML.ElemValue);
		xml.endTag(null, PwDatabaseV4XML.ElemString);
		
	}

	private void writeObject(String name, PwDeletedObject value) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && value != null);
		
		xml.startTag(null, name);
		
		writeObject(PwDatabaseV4XML.ElemUuid, value.getUuid());
		writeObject(PwDatabaseV4XML.ElemDeletionTime, value.getDeletionTime());
		
		xml.endTag(null, name);
	}

	private void writeList(Map<String, ProtectedBinary> binaries) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(binaries != null);
		
		for (Entry<String, ProtectedBinary> pair : binaries.entrySet()) {
			writeObject(pair.getKey(), pair.getValue());
		}
	}


	private void writeList(String name, List<PwDeletedObject> value) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && value != null);
		
		xml.startTag(null, name);
		
		for (PwDeletedObject pdo : value) {
			writeObject(PwDatabaseV4XML.ElemDeletedObject, pdo);
		}
		
		xml.endTag(null, name);
		
	}

	private void writeList(String name, MemoryProtectionConfig value) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && value != null);
		
		xml.startTag(null, name);
		
		writeObject(PwDatabaseV4XML.ElemProtTitle, value.getProtectTitle());
		writeObject(PwDatabaseV4XML.ElemProtUserName, value.getProtectUserName());
		writeObject(PwDatabaseV4XML.ElemProtPassword, value.getProtectPassword());
		writeObject(PwDatabaseV4XML.ElemProtURL, value.getProtectUrl());
		writeObject(PwDatabaseV4XML.ElemProtNotes, value.getProtectNotes());
		
		xml.endTag(null, name);
		
	}
	
	private void writeList(String name, Map<String, String> customData) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && customData != null);
		
		xml.startTag(null, name);
		
		for (Entry<String, String> pair : customData.entrySet()) {
			writeObject(PwDatabaseV4XML.ElemStringDictExItem, PwDatabaseV4XML.ElemKey, pair.getKey(), PwDatabaseV4XML.ElemValue, pair.getValue());
			  
		}
		
		xml.endTag(null, name);
		
	}
	
	private void writeList(String name, NodeV4Interface it) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && it != null);
		
		xml.startTag(null, name);
		
		writeObject(PwDatabaseV4XML.ElemLastModTime, it.getLastModificationTime().getDate());
		writeObject(PwDatabaseV4XML.ElemCreationTime, it.getCreationTime().getDate());
		writeObject(PwDatabaseV4XML.ElemLastAccessTime, it.getLastAccessTime().getDate());
		writeObject(PwDatabaseV4XML.ElemExpiryTime, it.getExpiryTime().getDate());
		writeObject(PwDatabaseV4XML.ElemExpires, it.isExpires());
		writeObject(PwDatabaseV4XML.ElemUsageCount, it.getUsageCount());
		writeObject(PwDatabaseV4XML.ElemLocationChanged, it.getLocationChanged().getDate());
		
		xml.endTag(null, name);
	}

	private void writeList(String name, List<PwEntryV4> value, boolean isHistory) throws IllegalArgumentException, IllegalStateException, IOException {
		assert(name != null && value != null);
		
		xml.startTag(null, name);
		
		for (PwEntryV4 entry : value) {
			writeEntry(entry, isHistory);
		}
		
		xml.endTag(null, name);
		
	}

	private void writeCustomIconList() throws IllegalArgumentException, IllegalStateException, IOException {
		List<PwIconCustom> customIcons = mPM.getCustomIcons();
		if (customIcons.size() == 0) return;
		
		xml.startTag(null, PwDatabaseV4XML.ElemCustomIcons);
		
		for (PwIconCustom icon : customIcons) {
			xml.startTag(null, PwDatabaseV4XML.ElemCustomIconItem);
			
			writeObject(PwDatabaseV4XML.ElemCustomIconItemID, icon.getUuid());
			writeObject(PwDatabaseV4XML.ElemCustomIconItemData, String.valueOf(Base64Coder.encode(icon.getImageData())));
			
			xml.endTag(null, PwDatabaseV4XML.ElemCustomIconItem);
		}
		
		xml.endTag(null, PwDatabaseV4XML.ElemCustomIcons);
	}
	
	private void writeBinPool() throws IllegalArgumentException, IllegalStateException, IOException {
		xml.startTag(null, PwDatabaseV4XML.ElemBinaries);
		
		for (Entry<Integer, ProtectedBinary> pair : mPM.getBinPool().entrySet()) {
			xml.startTag(null, PwDatabaseV4XML.ElemBinary);
			xml.attribute(null, PwDatabaseV4XML.AttrId, Integer.toString(pair.getKey()));
			
			subWriteValue(pair.getValue());
			
			xml.endTag(null, PwDatabaseV4XML.ElemBinary);
			
		}
		
		xml.endTag(null, PwDatabaseV4XML.ElemBinaries);
		
	}

	private String safeXmlString(String text) {
		if (EmptyUtils.isNullOrEmpty(text)) {
			return text;
		}
		
		StringBuilder sb = new StringBuilder();
		
		char ch;
		for (int i = 0; i < text.length(); i++) {
			ch = text.charAt(i);
			
			if(((ch >= 0x20) && (ch <= 0xD7FF)) ||              
			        (ch == 0x9) || (ch == 0xA) || (ch == 0xD) ||
			        ((ch >= 0xE000) && (ch <= 0xFFFD))) {
				
				sb.append(ch);
			}

		}
		
		return sb.toString();
	}

}
