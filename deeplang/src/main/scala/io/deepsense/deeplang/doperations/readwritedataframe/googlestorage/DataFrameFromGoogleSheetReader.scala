/**
 * Copyright 2016, deepsense.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.deepsense.deeplang.doperations.readwritedataframe.googlestorage

import java.io.{File => _}
import java.util.UUID

import org.apache.spark.sql._

import io.deepsense.commons.utils.Logging
import io.deepsense.deeplang.ExecutionContext
import io.deepsense.deeplang.doperations.inout.{InputFileFormatChoice, InputStorageTypeChoice}
import io.deepsense.deeplang.doperations.readwritedataframe.filestorage.DataFrameFromFileReader
import io.deepsense.deeplang.doperations.readwritedataframe.{FilePath, FileScheme}

object DataFrameFromGoogleSheetReader extends Logging {

  def readFromGoogleSheet(googleSheet: InputStorageTypeChoice.GoogleSheet)
                         (implicit context: ExecutionContext): DataFrame = {
    val id = googleSheet.getGoogleSheetId()
    val tmpPath = FilePath(
      FileScheme.File, s"/tmp/seahorse/google_sheet_${id}__${UUID.randomUUID()}.csv"
    )

    GoogleDriveClient.downloadGoogleSheetAsCsvFile(
      googleSheet.getGoogleServiceAccountCredentials(),
      googleSheet.getGoogleSheetId(),
      tmpPath.pathWithoutScheme
    )

    val readDownloadedGoogleFileParams = new InputStorageTypeChoice.File()
      .setFileFormat(
        new InputFileFormatChoice.Csv()
          .setCsvColumnSeparator(GoogleDriveClient.googleSheetCsvSeparator)
          .setShouldConvertToBoolean(googleSheet.getShouldConvertToBoolean)
          .setNamesIncluded(googleSheet.getNamesIncluded)
      ).setSourceFile(tmpPath.fullPath)

    DataFrameFromFileReader.readFromFile(readDownloadedGoogleFileParams)
  }

}
