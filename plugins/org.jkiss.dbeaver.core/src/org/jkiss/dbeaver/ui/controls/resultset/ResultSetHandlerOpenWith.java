/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
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
package org.jkiss.dbeaver.ui.controls.resultset;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IParameterValues;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tools.transfer.IDataTransferProcessor;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseProducerSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferProducer;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferNodeDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporter;
import org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Open results in external application
 */
public class ResultSetHandlerOpenWith extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(ResultSetHandlerOpenWith.class);

    public static final String CMD_OPEN_WITH = "org.jkiss.dbeaver.core.resultset.openWith";
    public static final String PARAM_PROCESSOR_ID = "processorId";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IResultSetController resultSet = ResultSetHandlerMain.getActiveResultSet(HandlerUtil.getActivePart(event));
        if (resultSet == null) {
            return null;
        }
        String processorId = event.getParameter(PARAM_PROCESSOR_ID);
        if (processorId == null) {
            return null;
        }
        switch (event.getCommand().getId()) {
            case CMD_OPEN_WITH:
                openResultsWith(resultSet, processorId);
                break;
        }
        return null;
    }

    private static void openResultsWith(IResultSetController resultSet, String processorId) {
        DataTransferProcessorDescriptor processor = DataTransferRegistry.getInstance().getProcessor(processorId);
        if (processor == null) {
            log.debug("Processor '" + processorId + "' not found");
            return;
        }
        openResultsWith(resultSet, processor);
    }

    private static void openResultsWith(IResultSetController resultSet, DataTransferProcessorDescriptor processor) {

        ResultSetDataContainerOptions options = new ResultSetDataContainerOptions();

        IResultSetSelection rsSelection = resultSet.getSelection();
        List<ResultSetRow> rsSelectedRows = rsSelection.getSelectedRows();
        if (rsSelectedRows.size() > 1) {
            List<Long> selectedRows = new ArrayList<>();
            for (ResultSetRow selectedRow : rsSelectedRows) {
                selectedRows.add((long) selectedRow.getRowNumber());
            }
            List<String> selectedAttributes = new ArrayList<>();
            for (DBDAttributeBinding attributeBinding : rsSelection.getSelectedAttributes()) {
                selectedAttributes.add(attributeBinding.getName());
            }

            options.setSelectedRows(selectedRows);
            options.setSelectedColumns(selectedAttributes);
        }
        ResultSetDataContainer dataContainer = new ResultSetDataContainer(resultSet.getDataContainer(), resultSet.getModel(), options);
        if (dataContainer.getDataSource() == null) {
            DBeaverUI.getInstance().showError("Open " + processor.getAppName(), "Not connected to a database");
            return;
        }


        AbstractJob exportJob = new AbstractJob("Open " + processor.getAppName()) {

            {
                setUser(true);
                setSystem(false);
            }

            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                try {
                    File tempDir = DBWorkbench.getPlatform().getTempFolder(monitor, "data-files");
                    File tempFile = new File(tempDir, new SimpleDateFormat(
                        "yyyyMMdd-HHmmss").format(System.currentTimeMillis()) + "." + processor.getAppFileExtension());
                    tempFile.deleteOnExit();

                    IDataTransferProcessor processorInstance = processor.getInstance();
                    if (!(processorInstance instanceof IStreamDataExporter)) {
                        return Status.CANCEL_STATUS;
                    }
                    IStreamDataExporter exporter = (IStreamDataExporter) processorInstance;

                    StreamTransferConsumer consumer = new StreamTransferConsumer();
                    StreamConsumerSettings settings = new StreamConsumerSettings();

                    settings.setOutputEncodingBOM(false);
                    settings.setOpenFolderOnFinish(false);
                    settings.setOutputFolder(tempDir.getAbsolutePath());
                    settings.setOutputFilePattern(tempFile.getName());

                    Map<Object, Object> properties = new HashMap<>();
                    for (DBPPropertyDescriptor prop : processor.getProperties()) {
                        properties.put(prop.getId(), prop.getDefaultValue());
                    }
                    // Remove extension property (we specify file name directly)
                    properties.remove(StreamConsumerSettings.PROP_FILE_EXTENSION);

                    consumer.initTransfer(dataContainer, settings, processor.isBinaryFormat(), exporter, properties);

                    DBDDataFilter dataFilter = resultSet.getModel().getDataFilter();
                    DatabaseTransferProducer producer = new DatabaseTransferProducer(dataContainer, dataFilter);
                    DatabaseProducerSettings producerSettings = new DatabaseProducerSettings();
                    producerSettings.setExtractType(DatabaseProducerSettings.ExtractType.SINGLE_QUERY);
                    producerSettings.setQueryRowCount(false);
                    producerSettings.setSelectedRowsOnly(true);
                    producerSettings.setSelectedColumnsOnly(true);

                    producer.transferData(monitor, consumer, null, producerSettings);

                    consumer.finishTransfer(monitor, false);

                    UIUtils.asyncExec(() -> {
                        if (!UIUtils.launchProgram(tempFile.getAbsolutePath())) {
                            DBeaverUI.getInstance().showError(
                                "Open " + processor.getAppName(),
                                "Can't open " + processor.getAppFileExtension() + " file '" + tempFile.getAbsolutePath() + "'");
                        }
                    });
                } catch (Exception e) {
                    DBeaverUI.getInstance().showError("Error opening in " + processor.getAppName(), null, e);
                }
                return Status.OK_STATUS;
            }
        };
        exportJob.schedule();
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        // Put processor name in command label
        String processorId = (String) parameters.get(PARAM_PROCESSOR_ID);
        if (processorId != null) {
            DataTransferProcessorDescriptor processor = DataTransferRegistry.getInstance().getProcessor(processorId);
            element.setText(processor.getAppName());
            if (!CommonUtils.isEmpty(processor.getDescription())) {
                element.setTooltip(processor.getDescription());
            }
            if (processor.getIcon() != null) {
                element.setIcon(DBeaverIcons.getImageDescriptor(processor.getIcon()));
            }
        }
    }

    public static class OpenWithParameterValues implements IParameterValues {

        @Override
        public Map<String,String> getParameterValues() {
            final Map<String,String> values = new HashMap<>();

            for (final DataTransferNodeDescriptor consumerNode : DataTransferRegistry.getInstance().getNodes(DataTransferNodeDescriptor.NodeType.CONSUMER)) {
                for (DataTransferProcessorDescriptor processor : consumerNode.getProcessors()) {
                    if (processor.getAppFileExtension() != null) {
                        values.put(processor.getAppName(), processor.getFullId());
                    }
                }
            }

            return values;
        }

    }
}
