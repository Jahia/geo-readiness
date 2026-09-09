import React, {useState} from 'react';
import ReactDOM from 'react-dom';
import PropTypes from 'prop-types';
import {useSelector} from 'react-redux';
import {useNodeChecks} from '@jahia/data-helper';
import {GeoReadinessDrawer} from './GeoReadinessDrawer';

export const GeoReadinessAction = ({path, language, render: Render, ...rest}) => {
    const [isOpen, setIsOpen] = useState(false);
    const reduxLanguage = useSelector(state => state.language);
    const jcontentPath = useSelector(state => (state.jcontent && state.jcontent.path) || null);

    const pagePath = path || jcontentPath;
    const lang = language || reduxLanguage || 'en';

    const {checksResult} = useNodeChecks(
        {path: pagePath},
        {
            showOnNodeTypes: ['jnt:page', 'jmix:mainResource'],
            requireModuleInstalledOnSite: ['geo-readiness']
        }
    );

    if (!pagePath || !checksResult) {
        return null;
    }

    return (
        <>
            <Render {...rest} onClick={() => setIsOpen(o => !o)}/>
            {ReactDOM.createPortal(
                <GeoReadinessDrawer
                    isOpen={isOpen}
                    path={pagePath}
                    language={lang}
                    onClose={() => setIsOpen(false)}
                />,
                document.body
            )}
        </>
    );
};

GeoReadinessAction.propTypes = {
    path: PropTypes.string,
    language: PropTypes.string,
    render: PropTypes.elementType.isRequired
};
